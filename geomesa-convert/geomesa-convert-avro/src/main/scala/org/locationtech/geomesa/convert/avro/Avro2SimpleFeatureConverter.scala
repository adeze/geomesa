package org.locationtech.geomesa.convert.avro

import com.typesafe.config.Config
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericDatumReader, GenericRecord}
import org.apache.avro.io.{BinaryDecoder, DecoderFactory}
import org.locationtech.geomesa.convert.Transformers.{Expr, Predicate}
import org.locationtech.geomesa.convert.{Converters, Field, ToSimpleFeatureConverter}
import org.opengis.feature.simple.SimpleFeatureType

import scala.collection.JavaConversions._

object Avro2SimpleFeatureConverterBuilder extends Converters[Array[Byte], Array[AnyRef]] {

  def apply(conf: Config): Avro2SimpleFeatureConverter = {
    val avroSchemaPath = conf.getString("schema")
    val avroSchema = new org.apache.avro.Schema.Parser().parse(getClass.getResourceAsStream(avroSchemaPath))
    val reader = new GenericDatumReader[GenericRecord](avroSchema)
    val fields = buildFields(conf.getConfigList("fields"))
    val targetSFT = findTargetSFT(conf.getString("sft"))
    val idBuilder = buildIdBuilder(conf.getString("id-field"))

    new Avro2SimpleFeatureConverter(avroSchema, reader, targetSFT, fields, idBuilder)
  }

  override def buildConverter(conf: Config): ToSimpleFeatureConverter[Array[Byte], Array[AnyRef]] = apply(conf)
}

class Avro2SimpleFeatureConverter(avroSchema: Schema,
                                  reader: GenericDatumReader[GenericRecord],
                                  val targetSFT: SimpleFeatureType,
                                  val inputFields: IndexedSeq[Field],
                                  val idBuilder: Expr)
  extends ToSimpleFeatureConverter[Array[Byte], Array[AnyRef]] {

  var decoder: BinaryDecoder = null
  var recordReuse: GenericRecord = null

  override def fromInputType(bytes: Array[Byte]): Array[AnyRef] = {
    decoder = DecoderFactory.get.binaryDecoder(bytes, decoder)
    Array(bytes, reader.read(recordReuse, decoder))
  }

  override def applyTransform(fn: Expr, t: Array[AnyRef]): Any = fn.eval(t: _*)

  override def applyPredicate(pred: Predicate, t: Array[AnyRef]): Boolean = pred.eval(t: _*)
}