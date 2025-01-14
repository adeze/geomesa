/***********************************************************************
* Copyright (c) 2013-2015 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.filter

import java.util.Date
import java.util.concurrent.TimeUnit

import com.vividsolutions.jts.geom.{Geometry, MultiPolygon, Polygon}
import org.joda.time.{DateTime, DateTimeZone, Interval}
import org.locationtech.geomesa.utils.geohash.GeohashUtils
import org.locationtech.geomesa.utils.geohash.GeohashUtils._
import org.locationtech.geomesa.utils.geotools.GeometryUtils
import org.locationtech.geomesa.utils.text.WKTUtils
import org.opengis.feature.simple.SimpleFeatureType
import org.opengis.filter._
import org.opengis.filter.expression.{Expression, Literal, PropertyName}
import org.opengis.filter.spatial._
import org.opengis.filter.temporal.{After, Before, During}
import org.opengis.temporal.Period

import scala.collection.JavaConversions._

object FilterHelper {
  // Let's handle special cases with topological filters.
  def updateTopologicalFilters(filter: Filter, featureType: SimpleFeatureType) = {
    filter match {
      case dw: DWithin    => rewriteDwithin(dw)
      case op: BBOX       => visitBBOX(op, featureType)
      case op: Within     => visitBinarySpatialOp(op, featureType)
      case op: Intersects => visitBinarySpatialOp(op, featureType)
      case op: Overlaps   => visitBinarySpatialOp(op, featureType)
      case _ => filter
    }
  }

  def visitBinarySpatialOp(op: BinarySpatialOperator, featureType: SimpleFeatureType): Filter = {
    val e1 = op.getExpression1.asInstanceOf[PropertyName]
    val e2 = op.getExpression2.asInstanceOf[Literal]
    val geom = e2.evaluate(null, classOf[Geometry])
    val safeGeometry = getInternationalDateLineSafeGeometry(geom)
    updateToIDLSafeFilter(op, safeGeometry, featureType)
  }

  def visitBBOX(op: BBOX, featureType: SimpleFeatureType): Filter = {
    val e1 = op.getExpression1.asInstanceOf[PropertyName]
    val e2 = op.getExpression2.asInstanceOf[Literal]
    val geom = addWayPointsToBBOX( e2.evaluate(null, classOf[Geometry]) )
    val safeGeometry = getInternationalDateLineSafeGeometry(geom)
    updateToIDLSafeFilter(op, safeGeometry, featureType)
  }

  def updateToIDLSafeFilter(op: BinarySpatialOperator, geom: Geometry, featureType: SimpleFeatureType): Filter = geom match {
    case p: Polygon =>
      dispatchOnSpatialType(op, featureType.getGeometryDescriptor.getLocalName, p)
    case mp: MultiPolygon =>
      val polygonList = getGeometryListOf(geom)
      val filterList = polygonList.map {
        p => dispatchOnSpatialType(op, featureType.getGeometryDescriptor.getLocalName, p)
      }
      ff.or(filterList)
  }

  def isFilterWholeWorld(f: Filter): Boolean = f match {
      case op: BBOX       => isOperationGeomWholeWorld(op)
      case op: Within     => isOperationGeomWholeWorld(op)
      case op: Intersects => isOperationGeomWholeWorld(op)
      case op: Overlaps   => isOperationGeomWholeWorld(op)
      case _ => false
    }

  private def isOperationGeomWholeWorld[Op <: BinarySpatialOperator](op: Op): Boolean = {
    val prop = checkOrder(op.getExpression1, op.getExpression2)
    prop.map(_.literal.evaluate(null, classOf[Geometry])).exists(isWholeWorld)
  }

  val minDateTime = new DateTime(0, 1, 1, 0, 0, 0, DateTimeZone.forID("UTC"))
  val maxDateTime = new DateTime(9999, 12, 31, 23, 59, 59, DateTimeZone.forID("UTC"))
  val everywhen = new Interval(minDateTime, maxDateTime)
  val everywhere = WKTUtils.read("POLYGON((-180 -90, 0 -90, 180 -90, 180 90, 0 90, -180 90, -180 -90))").asInstanceOf[Polygon]

  def isWholeWorld[G <: Geometry](g: G): Boolean = g != null && g.union.covers(everywhere)

  def getGeometryListOf(inMP: Geometry): Seq[Geometry] =
    for( i <- 0 until inMP.getNumGeometries ) yield inMP.getGeometryN(i)

  def dispatchOnSpatialType(op: BinarySpatialOperator, property: String, geom: Geometry): Filter = op match {
    case op: Within     => ff.within( ff.property(property), ff.literal(geom) )
    case op: Intersects => ff.intersects( ff.property(property), ff.literal(geom) )
    case op: Overlaps   => ff.overlaps( ff.property(property), ff.literal(geom) )
    case op: BBOX       => val envelope = geom.getEnvelopeInternal
      ff.bbox( ff.property(property), envelope.getMinX, envelope.getMinY,
        envelope.getMaxX, envelope.getMaxY, op.getSRS )
  }

  def addWayPointsToBBOX(g: Geometry): Geometry = {
    val gf = g.getFactory
    val geomArray = g.getCoordinates
    val correctedGeom = GeometryUtils.addWayPoints(geomArray).toArray
    gf.createPolygon(correctedGeom)
  }

  // Rewrites a Dwithin (assumed to express distance in meters) in degrees.
  def rewriteDwithin(op: DWithin): Filter = {
    val e2 = op.getExpression2.asInstanceOf[Literal]
    val geom = e2.getValue.asInstanceOf[Geometry]
    val distanceDegrees = GeometryUtils.distanceDegrees(geom, op.getDistance)

    // NB: The ECQL spec doesn't allow for us to put the measurement in "degrees",
    //  but that's how this filter will be used.
    ff.dwithin(
      op.getExpression1,
      op.getExpression2,
      distanceDegrees,
      "meters")
  }

  def decomposeToGeometry(f: Filter): Seq[Geometry] = f match {
    case bbox: BBOX =>
      val bboxPoly = bbox.getExpression2.asInstanceOf[Literal].evaluate(null, classOf[Geometry])
      Seq(bboxPoly)
    case gf: BinarySpatialOperator =>
      extractGeometry(gf)
    case _ => Seq()
  }

  def extractGeometry(bso: BinarySpatialOperator): Seq[Geometry] = {
    bso match {
      // The Dwithin has already between rewritten.
      case dwithin: DWithin =>
        val e2 = dwithin.getExpression2.asInstanceOf[Literal]
        val geom = e2.getValue.asInstanceOf[Geometry]
        val buffer = dwithin.getDistance
        val bufferedGeom = geom.buffer(buffer)
        Seq(GeohashUtils.getInternationalDateLineSafeGeometry(bufferedGeom))
      case bs =>
        bs.getExpression1.evaluate(null, classOf[Geometry]) match {
          case g: Geometry => Seq(GeohashUtils.getInternationalDateLineSafeGeometry(g))
          case _           =>
            bso.getExpression2.evaluate(null, classOf[Geometry]) match {
              case g: Geometry => Seq(GeohashUtils.getInternationalDateLineSafeGeometry(g))
            }
        }
    }
  }

  // NB: This method assumes that the filters represent a collection of 'and'ed temporal filters.
  def extractInterval(filters: Seq[Filter], dtField: Option[String], offsetDuring: Boolean = false): Interval = {
    import org.locationtech.geomesa.utils.filters.Typeclasses.BinaryFilter
    import org.locationtech.geomesa.utils.filters.Typeclasses.BinaryFilter.ops

    def endpointFromBinaryFilter[B: BinaryFilter](b: B, dtfn: String) = {
      val exprToDT: Expression => DateTime = ex => new DateTime(ex.evaluate(null, classOf[Date]))
      if (b.left.toString == dtfn) {
        Right(exprToDT(b.right))  // the left side is the field name; the right is the endpoint
      } else {
        Left(exprToDT(b.left))    // the right side is the field name; the left is the endpoint
      }
    }

    def intervalFromAfterLike[B: BinaryFilter](b: B, dtfn: String) =
      endpointFromBinaryFilter(b, dtfn) match {
        case Right(dt) => new Interval(dt, maxDateTime)
        case Left(dt)  => new Interval(minDateTime, dt)
      }

    def intervalFromBeforeLike[B: BinaryFilter](b: B, dtfn: String) =
      endpointFromBinaryFilter(b, dtfn) match {
        case Right(dt) => new Interval(minDateTime, dt)
        case Left(dt)  => new Interval(dt, maxDateTime)
      }

    def extractInterval(dtfn: String): Filter => Interval = {
      case during: During =>
        val p = during.getExpression2.evaluate(null, classOf[Period])
        val start = new DateTime(p.getBeginning.getPosition.getDate)
        val end = new DateTime(p.getEnding.getPosition.getDate)
        if (offsetDuring) {
          // round up/down to the next second
          val s = start.minusMillis(start.getMillisOfSecond).plusSeconds(1)
          val endMillis = end.getMillisOfSecond
          val e = if (endMillis == 0) end.minusSeconds(1) else end.minusMillis(endMillis)
          new Interval(s, e)
        } else {
          new Interval(start, end)
        }

      case between: PropertyIsBetween =>
        val start = between.getLowerBoundary.evaluate(null, classOf[Date])
        val end = between.getUpperBoundary.evaluate(null, classOf[Date])
        new Interval(start.getTime, end.getTime)
      // NB: Interval semantics correspond to "at or after"
      case after: After =>                        intervalFromAfterLike(after, dtfn)
      case before: Before =>                      intervalFromBeforeLike(before, dtfn)

      case lt: PropertyIsLessThan =>              intervalFromBeforeLike(lt, dtfn)
      // NB: Interval semantics correspond to <
      case le: PropertyIsLessThanOrEqualTo =>     intervalFromBeforeLike(le, dtfn)
      // NB: Interval semantics correspond to >=
      case gt: PropertyIsGreaterThan =>           intervalFromAfterLike(gt, dtfn)
      case ge: PropertyIsGreaterThanOrEqualTo =>  intervalFromAfterLike(ge, dtfn)
      case a: Any =>
        throw new Exception(s"Expected temporal filters.  Received an $a.")
    }

    dtField match {
      case None => everywhen
      case Some(dtfn) => filters.map(extractInterval(dtfn)).fold(everywhen)( _ overlap _)
    }
  }

  def filterListAsAnd(filters: Seq[Filter]): Option[Filter] = filters match {
    case Nil => None
    case _ => Some(recomposeAnd(filters))
  }

  def recomposeAnd(s: Seq[Filter]): Filter = if (s.tail.isEmpty) s.head else ff.and(s)

  /**
   * Finds the first filter satisfying the condition and returns the rest in the same order they were in
   */
  def findFirst(pred: Filter => Boolean)(s: Seq[Filter]): (Option[Filter], Seq[Filter]) =
    if (s.isEmpty) (None, s) else {
      val h = s.head
      val t = s.tail
      if (pred(h)) (Some(h), t) else {
        val (x, xs) = findFirst(pred)(t)
        (x, h +: xs)
      }
    }

  /**
   * Finds the filter with the lowest known cost and returns the rest in the same order they were in.  If
   * there are multiple filters with the same lowest cost then the first will be selected.  If no filters
   * have a known cost or if ``s`` is empty then (None, s) will be returned.
   */
  def findBest(cost: Filter => Option[Long])(s: Seq[Filter]): CostAnalysis = {
    if (s.isEmpty) {
      CostAnalysis.unknown(s)
    } else {
      val head = s.head
      val tail = s.tail

      val headAnalysis = cost(head).map(c => new KnownCost(head, tail, c)).getOrElse(CostAnalysis.unknown(s))
      val tailAnalysis = findBest(cost)(tail)

      if (headAnalysis <= tailAnalysis) {
        headAnalysis
      } else {
        // tailAnaysis must have a known cost
        val ta = tailAnalysis.asInstanceOf[KnownCost]
        new KnownCost(ta.best, head +: ta.otherFilters, ta.cost)
      }
    }
  }

  /**
    * @param bestFilter the [[Filter]] with the lowest cost
    * @param otherFilters all other [[Filter]]s
    */
  sealed abstract case class CostAnalysis(bestFilter: Option[Filter], otherFilters: Seq[Filter]) {

    /**
      * @param rhs the [[CostAnalysis]] to compare to
      * @return ``true`` if ``this`` has a lower or the same cost as ``rhs``
      */
    def <=(rhs: CostAnalysis): Boolean

    def extract: (Option[Filter], Seq[Filter]) = (bestFilter, otherFilters)
  }

  class KnownCost(val best: Filter, others: Seq[Filter], val cost: Long) extends CostAnalysis(Some(best), others) {

    def <=(rhs: CostAnalysis): Boolean = rhs match {
      case knownRhs: KnownCost =>
        this.cost <= knownRhs.cost
      case _ =>
        // always less than an unknown cost
        true
    }
  }

  class UnknownCost(filters: Seq[Filter]) extends CostAnalysis(None, filters) {
    override def <=(rhs: CostAnalysis): Boolean = rhs.isInstanceOf[UnknownCost]
  }

  object CostAnalysis {

    /**
      * @param filters the filters, none of which have a known cost
      * @return an [[UnknownCost]] containing ``filters``
      */
    def unknown(filters: Seq[Filter]): CostAnalysis = new UnknownCost(filters)
  }

  def decomposeAnd(f: Filter): Seq[Filter] = {
    f match {
      case b: And => b.getChildren.toSeq.flatMap(decomposeAnd)
      case f: Filter => Seq(f)
    }
  }
}

