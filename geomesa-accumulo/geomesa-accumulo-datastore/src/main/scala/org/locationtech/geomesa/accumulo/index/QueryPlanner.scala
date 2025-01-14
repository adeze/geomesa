/***********************************************************************
* Copyright (c) 2013-2015 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.accumulo.index

import java.util.Map.Entry

import com.vividsolutions.jts.geom.Geometry
import org.apache.accumulo.core.data.{Key, Range => AccRange, Value}
import org.geotools.data.Query
import org.geotools.factory.Hints
import org.geotools.feature.AttributeTypeBuilder
import org.geotools.feature.simple.SimpleFeatureTypeBuilder
import org.geotools.filter.FunctionExpressionImpl
import org.geotools.geometry.jts.ReferencedEnvelope
import org.geotools.process.vector.TransformProcess
import org.geotools.process.vector.TransformProcess.Definition
import org.locationtech.geomesa.accumulo.data._
import org.locationtech.geomesa.accumulo.index.QueryHints._
import org.locationtech.geomesa.accumulo.index.QueryPlanners.FeatureFunction
import org.locationtech.geomesa.accumulo.index.Strategy.StrategyType.StrategyType
import org.locationtech.geomesa.accumulo.iterators._
import org.locationtech.geomesa.accumulo.util.CloseableIterator._
import org.locationtech.geomesa.accumulo.util.{CloseableIterator, SelfClosingIterator}
import org.locationtech.geomesa.features.SerializationType.SerializationType
import org.locationtech.geomesa.features._
import org.locationtech.geomesa.filter._
import org.locationtech.geomesa.security.SecurityUtils
import org.locationtech.geomesa.utils.cache.SoftThreadLocal
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.stats.{MethodProfiling, TimingsImpl}
import org.opengis.feature.GeometryAttribute
import org.opengis.feature.`type`.{AttributeDescriptor, GeometryDescriptor}
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter
import org.opengis.filter.expression.PropertyName
import org.opengis.filter.sort.{SortBy, SortOrder}

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

/**
 * Executes a query against geomesa
 */
case class QueryPlanner(sft: SimpleFeatureType,
                        featureEncoding: SerializationType,
                        stSchema: String,
                        acc: AccumuloConnectorCreator,
                        hints: StrategyHints) extends ExplainingLogging with MethodProfiling {

  import org.locationtech.geomesa.accumulo.index.QueryPlanner._

  val featureEncoder = SimpleFeatureSerializers(sft, featureEncoding)
  val featureDecoder = SimpleFeatureDeserializers(sft, featureEncoding)
  val indexValueEncoder = IndexValueEncoder(sft)

  /**
   * Plan the query, but don't execute it - used for m/r jobs and explain query
   */
  def planQuery(query: Query,
                strategy: Option[StrategyType] = None,
                output: ExplainerOutputType = log): Seq[QueryPlan] = {
    getQueryPlans(query, strategy, output)._1
  }

  /**
   * Execute a query against geomesa
   */
  def runQuery(query: Query, strategy: Option[StrategyType] = None): SFIter = {
    val (plans, numClauses) = getQueryPlans(query, strategy, log)
    // don't deduplicate density queries, as they don't have dupes but re-use feature ids in the results
    val dedupe = !query.getHints.isDensityQuery && (numClauses > 1 || plans.exists(_.hasDuplicates))
    executePlans(query, plans, dedupe)
  }

  /**
   * Execute a sequence of query plans
   */
  private def executePlans(query: Query, queryPlans: Seq[QueryPlan], deduplicate: Boolean): SFIter = {
    def scan(qps: Seq[QueryPlan]): SFIter = qps.iterator.ciFlatMap { qp =>
      Strategy.execute(qp, acc, log).map(qp.kvsToFeatures)
    }

    def dedupe(iter: SFIter): SFIter = if (deduplicate) new DeDuplicatingIterator(iter) else iter

    // noinspection EmptyCheck
    def sort(iter: SFIter): SFIter = if (query.getSortBy != null && query.getSortBy.length > 0) {
      // sort will self-close itself
      new LazySortedIterator(iter, query.getHints.getReturnSft, query.getSortBy)
    } else {
      // wrap in a self-closing iterator to mitigate clients not calling close
      SelfClosingIterator(iter)
    }

    def reduce(iter: SFIter): SFIter = if (query.getHints.isTemporalDensityQuery) {
      TemporalDensityIterator.reduceTemporalFeatures(iter, query)
    } else if (query.getHints.isMapAggregatingQuery) {
      MapAggregatingIterator.reduceMapAggregationFeatures(iter, query)
    } else {
      iter
    }

    reduce(sort(dedupe(scan(queryPlans))))
  }

  /**
   * Set up the query plans and strategies used to execute them.
   * Returns the strategy plans and the number of distinct OR clauses, needed for determining deduplication
   */
  private def getQueryPlans(query: Query,
                            requested: Option[StrategyType],
                            output: ExplainerOutputType): (Seq[QueryPlan], Int) = {

    configureQuery(query, sft) // configure the query - set hints that we'll need later on

    output(s"Planning '${query.getTypeName}' ${filterToString(query.getFilter)}")
    output(s"Hints: density ${query.getHints.isDensityQuery}, bin ${query.getHints.isBinQuery}")
    output(s"Sort: ${Option(query.getSortBy).filter(_.nonEmpty).map(_.mkString(", ")).getOrElse("none")}")
    output(s"Transforms: ${query.getHints.getTransformDefinition.getOrElse("None")}")

    if (query.getHints.isDensityQuery) { // add the bbox from the density query to the filter
      val env = query.getHints.getDensityEnvelope.get.asInstanceOf[ReferencedEnvelope]
      val bbox = ff.bbox(ff.property(sft.getGeometryDescriptor.getLocalName), env)
      if (query.getFilter == Filter.INCLUDE) {
        query.setFilter(bbox)
      } else {
        // add the bbox - try to not duplicate an existing bbox
        val filter = andFilters((decomposeAnd(query.getFilter) ++ Seq(bbox)).distinct)
        query.setFilter(filter)
      }
    }

    implicit val timings = new TimingsImpl
    val (queryPlans, numClauses) = profile({
      val strategies = QueryStrategyDecider.chooseStrategies(sft, query, hints, requested, output)
      val plans = strategies.flatMap { strategy =>
        output(s"Strategy: ${strategy.getClass.getSimpleName}")
        output(s"Filter: ${strategy.filter}")
        val plans = strategy.getQueryPlans(this, query.getHints, output)
        plans.foreach(outputPlan(_, output))
        plans
      }
      (plans, strategies.length)
    }, "plan")
    output(s"Query planning took ${timings.time("plan")}ms for $numClauses " +
        s"distinct quer${if (numClauses == 1) "y" else "ies"}.")
    (queryPlans, numClauses)
  }

  // output the query plan for explain logging
  private def outputPlan(plan: QueryPlan, output: ExplainerOutputType, prefix: String = ""): Unit = {
    output(s"${prefix}Table: ${plan.table}")
    output(s"${prefix}Column Families${if (plan.columnFamilies.isEmpty) ": all"
      else s" (${plan.columnFamilies.size}): ${plan.columnFamilies.take(20)}"} ")
    output(s"${prefix}Ranges (${plan.ranges.size}): ${plan.ranges.take(5).map(rangeToString).mkString(", ")}")
    output(s"${prefix}Iterators (${plan.iterators.size}): ${plan.iterators.mkString("[", "],[", "]")}")
    plan.join.foreach(j => outputPlan(j._2, output, "Join "))
  }

  // converts a range to a printable string - only includes the row
  private def rangeToString(r: AccRange): String = {
    val a = if (r.isStartKeyInclusive) "[" else "("
    val z = if (r.isEndKeyInclusive) "]" else ")"
    val start = if (r.isInfiniteStartKey) "-inf" else keyToString(r.getStartKey)
    val stop = if (r.isInfiniteStopKey) "+inf" else keyToString(r.getEndKey)
    s"$a$start::$stop$z"
  }

  // converts a key to a printable string - only includes the row
  private def keyToString(k: Key): String =
    Key.toPrintableString(k.getRow.getBytes, 0, k.getRow.getLength, k.getRow.getLength)

  // This function decodes/transforms that Iterator of Accumulo Key-Values into an Iterator of SimpleFeatures
  def defaultKVsToFeatures(hints: Hints): FeatureFunction = kvsToFeatures(hints.getReturnSft)

  // This function decodes/transforms that Iterator of Accumulo Key-Values into an Iterator of SimpleFeatures
  def kvsToFeatures(sft: SimpleFeatureType): FeatureFunction = {
    // Perform a projecting decode of the simple feature
    val deserializer = SimpleFeatureDeserializers(sft, featureEncoding)
    (kv: Entry[Key, Value]) => {
      val sf = deserializer.deserialize(kv.getValue.get)
      applyVisibility(sf, kv.getKey)
      sf
    }
  }
}

object QueryPlanner {

  val iteratorPriority_RowRegex                        = 0
  val iteratorPriority_AttributeIndexFilteringIterator = 10
  val iteratorPriority_AttributeIndexIterator          = 200
  val iteratorPriority_AttributeUniqueIterator         = 300
  val iteratorPriority_ColFRegex                       = 100
  val iteratorPriority_SpatioTemporalIterator          = 200
  val iteratorPriority_SimpleFeatureFilteringIterator  = 300
  val iteratorPriority_AnalysisIterator                = 400

  type KVIter = CloseableIterator[Entry[Key,Value]]
  type SFIter = CloseableIterator[SimpleFeature]

  private val threadedHints = new SoftThreadLocal[Map[AnyRef, AnyRef]]

  def setPerThreadQueryHints(hints: Map[AnyRef, AnyRef]): Unit = threadedHints.put(hints)
  def clearPerThreadQueryHints() = threadedHints.clear()

  def configureQuery(query: Query, sft: SimpleFeatureType): Unit = {
    // Query.ALL does not support setting query hints, which we need for our workflow
    require(query != Query.ALL, "Query.ALL is not supported - please use 'new Query(schemaName)' instead")

    // set query hints - we need this in certain situations where we don't have access to the query directly
    QueryPlanner.threadedHints.get.foreach { hints =>
      hints.foreach { case (k, v) => query.getHints.put(k, v) }
      // clear any configured hints so we don't process them again
      threadedHints.clear()
    }
    // set transformations in the query
    QueryPlanner.setQueryTransforms(query, sft)
    // set return SFT in the query
    QueryPlanner.setReturnSft(query, sft)
  }

  /**
   * Checks for attribute transforms in the query and sets them as hints if found
   *
   * @param query
   * @param sft
   * @return
   */
  def setQueryTransforms(query: Query, sft: SimpleFeatureType) =
    if (query.getProperties != null && !query.getProperties.isEmpty) {
      val (transformProps, regularProps) = query.getPropertyNames.partition(_.contains('='))
      val convertedRegularProps = regularProps.map { p => s"$p=$p" }
      val allTransforms = convertedRegularProps ++ transformProps
      // ensure that the returned props includes geometry, otherwise we get exceptions everywhere
      val geomName = sft.getGeometryDescriptor.getLocalName
      val geomTransform = if (allTransforms.exists(_.matches(s"$geomName\\s*=.*"))) {
        Nil
      } else {
        Seq(s"$geomName=$geomName")
      }
      val transforms = (allTransforms ++ geomTransform).mkString(";")
      val transformDefs = TransformProcess.toDefinition(transforms)
      val derivedSchema = computeSchema(sft, transformDefs.asScala)
      query.setProperties(Query.ALL_PROPERTIES)
      query.getHints.put(TRANSFORMS, transforms)
      query.getHints.put(TRANSFORM_SCHEMA, derivedSchema)
    }

  private def computeSchema(origSFT: SimpleFeatureType, transforms: Seq[Definition]): SimpleFeatureType = {
    val attributes: Seq[AttributeDescriptor] = transforms.map { definition =>
      val name = definition.name
      val cql  = definition.expression
      cql match {
        case p: PropertyName =>
          val origAttr = origSFT.getDescriptor(p.getPropertyName)
          val ab = new AttributeTypeBuilder()
          ab.init(origAttr)
          val descriptor = if (origAttr.isInstanceOf[GeometryDescriptor]) {
            ab.buildDescriptor(name, ab.buildGeometryType())
          } else {
            ab.buildDescriptor(name, ab.buildType())
          }
          descriptor.getUserData.putAll(origAttr.getUserData)
          descriptor

        case f: FunctionExpressionImpl  =>
          val clazz = f.getFunctionName.getReturn.getType
          val ab = new AttributeTypeBuilder().binding(clazz)
          if (classOf[Geometry].isAssignableFrom(clazz)) {
            ab.buildDescriptor(name, ab.buildGeometryType())
          } else {
            ab.buildDescriptor(name, ab.buildType())
          }
      }
    }

    val geomAttributes = attributes.filter(_.isInstanceOf[GeometryAttribute]).map(_.getLocalName)
    val sftBuilder = new SimpleFeatureTypeBuilder()
    sftBuilder.setName(origSFT.getName)
    sftBuilder.addAll(attributes.toArray)
    if (geomAttributes.nonEmpty) {
      val defaultGeom = if (geomAttributes.size == 1) { geomAttributes.head } else {
        // try to find a geom with the same name as the original default geom
        val origDefaultGeom = origSFT.getGeometryDescriptor.getLocalName
        geomAttributes.find(_ == origDefaultGeom).getOrElse(geomAttributes.head)
      }
      sftBuilder.setDefaultGeometry(defaultGeom)
    }
    val schema = sftBuilder.buildFeatureType()
    schema.getUserData.putAll(origSFT.getUserData)
    schema
  }

  def splitQueryOnOrs(query: Query, output: ExplainerOutputType): Seq[Query] = {
    val originalFilter = query.getFilter
    output(s"Original filter: $originalFilter")

    val rewrittenFilter = rewriteFilterInDNF(originalFilter)
    output(s"Rewritten filter: $rewrittenFilter")

    val orSplitter = new OrSplittingFilter
    val splitFilters = orSplitter.visit(rewrittenFilter, null)

    // Let's just check quickly to see if we can eliminate any duplicates.
    val filters = splitFilters.distinct

    filters.map { filter =>
      val q = new Query(query)
      q.setFilter(filter)
      q
    }
  }

  def applyVisibility(sf: SimpleFeature, key: Key): Unit = {
    val visibility = key.getColumnVisibility
    if (!EMPTY_VIZ.equals(visibility)) {
      SecurityUtils.setFeatureVisibility(sf, visibility.toString)
    }
  }

  // This function calculates the SimpleFeatureType of the returned SFs.
  private def setReturnSft(query: Query, baseSft: SimpleFeatureType): SimpleFeatureType = {
    val sft = if (query.getHints.isBinQuery) {
      BinAggregatingIterator.BIN_SFT
    } else if (query.getHints.isDensityQuery) {
      Z3DensityIterator.DENSITY_SFT
    } else if (query.getHints.containsKey(TEMPORAL_DENSITY_KEY)) {
      TemporalDensityIterator.createFeatureType(baseSft)
    } else if (query.getHints.containsKey(MAP_AGGREGATION_KEY)) {
      val mapAggregationAttribute = query.getHints.get(MAP_AGGREGATION_KEY).asInstanceOf[String]
      val spec = MapAggregatingIterator.projectedSFTDef(mapAggregationAttribute, baseSft)
      SimpleFeatureTypes.createType(baseSft.getTypeName, spec)
    } else {
      query.getHints.getTransformSchema.getOrElse(baseSft)
    }
    query.getHints.put(RETURN_SFT_KEY, sft)
    sft
  }
}

class LazySortedIterator(features: CloseableIterator[SimpleFeature],
                         sft: SimpleFeatureType,
                         sortBy: Array[SortBy]) extends CloseableIterator[SimpleFeature] {

  private lazy val sorted: CloseableIterator[SimpleFeature] = {

    val sortOrdering = sortBy.map {
      case SortBy.NATURAL_ORDER => Ordering.by[SimpleFeature, String](_.getID)
      case SortBy.REVERSE_ORDER => Ordering.by[SimpleFeature, String](_.getID).reverse
      case sb                   =>
        val prop = sb.getPropertyName.getPropertyName
        val idx = sft.indexOf(prop)
        require(idx != -1, s"Trying to sort on unavailable property '$prop' in feature type " +
            s"'${SimpleFeatureTypes.encodeType(sft)}'")
        val ord  = attributeToComparable(idx)
        if (sb.getSortOrder == SortOrder.DESCENDING) ord.reverse else ord
    }
    val comp: (SimpleFeature, SimpleFeature) => Boolean =
      if (sortOrdering.length == 1) {
        // optimized case for one ordering
        val ret = sortOrdering.head
        (l, r) => ret.compare(l, r) < 0
      }  else {
        (l, r) => sortOrdering.map(_.compare(l, r)).find(_ != 0).getOrElse(0) < 0
      }
    // use ListBuffer for constant append time and size
    val buf = scala.collection.mutable.ListBuffer.empty[SimpleFeature]
    while (features.hasNext) {
      buf.append(features.next())
    }
    features.close()
    CloseableIterator(buf.sortWith(comp).iterator)
  }

  def attributeToComparable[T <: Comparable[T]](i: Int)(implicit ct: ClassTag[T]): Ordering[SimpleFeature] =
    Ordering.by[SimpleFeature, T](_.getAttribute(i).asInstanceOf[T])(new Ordering[T] {
      val evo = implicitly[Ordering[T]]

      override def compare(x: T, y: T): Int = {
        if (x == null) {
          if (y == null) { 0 } else { -1 }
        } else if (y == null) {
          1
        } else {
          evo.compare(x, y)
        }
      }
    })

  override def hasNext: Boolean = sorted.hasNext

  override def next(): SimpleFeature = sorted.next()

  override def close(): Unit = features.close()
}

