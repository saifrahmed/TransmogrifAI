/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 */

package com.salesforce.op.stages.impl.insights

import com.salesforce.op.UID
import com.salesforce.op.stages.base.binary.{BinaryEstimator, BinaryModel}
import com.salesforce.op.stages.impl.preparators.CorrelationType
import com.salesforce.op.utils.spark.{OpVectorColumnHistory, OpVectorMetadata}
import enumeratum.{Enum, EnumEntry}
import org.apache.spark.sql.Dataset
import org.apache.spark.mllib.linalg.{Vectors => OldVectors}
import org.apache.spark.ml.linalg.{DenseVector, Matrix, SparseVector, Vector}
import org.apache.spark.ml.param.{IntParam, Param}
import org.apache.spark.mllib.stat.{MultivariateStatisticalSummary, Statistics}
import com.twitter.algebird.Operators._
import com.salesforce.op.features.types._
import com.salesforce.op.stages.impl.CheckIsResponseValues
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.{write => jwrite}

import scala.util.Try

/**
 * Creates record level insights for model predictions. Takes two inputs the first is the predictions to explain
 * and the second feature vector fed into the model. Note that regression outputs must be converted into a vector
 * with one column in order to use this estimator
 * @param uid           uid for instance
 */
class RecordInsightsCorr(uid: String = UID[RecordInsightsCorr]) extends
  BinaryEstimator[OPVector, OPVector, TextMap](operationName = "recordInsightsCorr", uid = uid) {

  /**
   * Note it is not strictly necessary for in1 to be a response but this is the only way of verifying
   * the order of the inputs
   */
  override protected def onSetInput(): Unit = {
    CheckIsResponseValues(in1, in2)
  }

  final val normType = new Param[NormType](
    parent = this, name = "normType",
    doc = "Which type of normalization to do before computing feature importances"
  )
  def setNormType(value: NormType): this.type = set(normType, value)
  def getNormType: NormType = $(normType)


  final val correlationType = new Param[CorrelationType](
    parent = this, name = "correlationType",
    doc = "Which coefficient to use for computing correlation of features to scores"
  )
  def setCorrelationType(value: CorrelationType): this.type = set(correlationType, value)
  def getCorrelationType: CorrelationType = $(correlationType)


  final val topK = new IntParam(
    parent = this, name = "topK",
    doc = "Number of insights to keep for each record"
  )
  def setTopK(value: Int): this.type = set(topK, value)
  def getTopK: Int = $(topK)

  setDefault(
    normType -> NormType.MinMax,
    correlationType -> CorrelationType.Pearson,
    topK -> 20
  )

  override def fitFn(dataset: Dataset[(OPVector#Value, OPVector#Value)]): RecordInsightsCorrModel = {

    val vectorMetadata = Try(OpVectorMetadata(getInputSchema()(in2.name)))
    assert(vectorMetadata.isSuccess, s"first input feature must be a feature vector with OpVectorMetadata," +
      s"got error parsing metadata: ${vectorMetadata.failed.get}")

    val first = dataset.first()
    val (psize, fsize) = (first._1.size, first._2.size) // first input must be the predictions

    val combinedVector = dataset.rdd.map{
      case (pred: Vector, features: SparseVector) =>
        val sparsePred = pred match { case p: SparseVector => p case p: DenseVector => p.toSparse}
        OldVectors.sparse(size = psize + fsize, indices = features.indices ++ sparsePred.indices.map( _ + fsize),
          values = features.values ++ sparsePred.values)
      case (pred: Vector, features: DenseVector) =>
        val densePred = pred match { case p: SparseVector => p.toDense case p: DenseVector => p}
        OldVectors.dense(features.values ++ densePred.values)
    }.persist()

    val scoreCorr = Statistics.corr(combinedVector, getCorrelationType.name)
      .colIter.slice(fsize, fsize + psize)
      .map(_.toArray).toArray

    val columnStats = Statistics.colStats(combinedVector) // TODO look into using sanity checker when available
    val norm = getNormType.makeNormalizer(columnStats)
    new RecordInsightsCorrModel(getTopK, fsize, psize, scoreCorr, norm, operationName, uid)
  }
}

private[op] final class RecordInsightsCorrModel
(
  val topK: Int,
  val featureSize: Int,
  val predictionSize: Int,
  val scoreCorr: Array[Array[Double]],
  val norm: Normalizer,
  operationName: String,
  uid: String
) extends BinaryModel[OPVector, OPVector, TextMap](operationName = operationName, uid = uid) {

  override protected def onSetInput(): Unit = {
    CheckIsResponseValues(in1, in2)
  }

  private lazy val featureInfo = OpVectorMetadata(getInputSchema()(in2.name)).getColumnHistory()

  override def transformFn: (OPVector, OPVector) => TextMap = (_, features) => {
    assert(featureInfo.size == features.value.size, "feature metadata size does not match feature size")

    val normalizedFeatures = norm(features)
    val importance = scoreCorr.map{ _.zip(normalizedFeatures).map{ case (a, b) => if (a.isNaN) 0.0 else a * b} }
    val topForEachPred = importance.zipWithIndex.map{
      case (imp, ind) => featureInfo.zip(imp)
        .sortBy(i => -math.abs(i._2))
        .take(topK)
        .map{ case (col, i) => col -> Seq(ind -> i)}
        .toMap
    }
    // map returned will is column json of column history -> json of tuples(pred index, importance)
    topForEachPred.fold(Map.empty[OpVectorColumnHistory, RecordInsightsParser.Insights])(_ + _)
      .map(RecordInsightsParser.insightToText)
      .toTextMap
  }
}


object RecordInsightsParser {

  /**
   * All insights take the for sequence of tuples from index of prediction explained to importance value
   */
  type Insights = Seq[(Int, Double)]

  def insightToText(insight: (OpVectorColumnHistory, Insights)): (String, String) = {
    implicit val formats = Serialization.formats(ShortTypeHints(List(classOf[Tuple2[Int, Double]])))
    insight._1.toJson(false) -> jwrite(insight._2)
  }

  def parseInsights(insights: TextMap): Map[OpVectorColumnHistory, Insights] = {
    implicit val formats: DefaultFormats = DefaultFormats
    insights.value.map { case (k, v) => OpVectorColumnHistory.fromJson(k) -> parse(v).extract[Seq[(Int, Double)]] }
  }
}

/**
 * Represents a kind of scaling to do on feature before computing importance
 *
 * @param name the name of the importance
 */
sealed abstract class NormType(val name: String) extends EnumEntry with Serializable {
  def makeNormalizer(summary: MultivariateStatisticalSummary): Normalizer
}

object NormType extends Enum[NormType] {
  val values: Seq[NormType] = findValues
  /**
   * MinMax scaling: x - min / (max - min)
   */
  case object MinMax extends NormType("minMax") {
    def makeNormalizer(summary: MultivariateStatisticalSummary): Normalizer = {
      val (min, max) = (summary.min.toArray, summary.max.toArray)
      val range = max.zip(min).map{ case (mx, mn) => mx - mn}
      Normalizer(min, range, 0.0, name)
    }
  }
  /**
   * Znorm scaling: x - mean / stdv
   */
  case object Znorm extends NormType("zNorm") {
    def makeNormalizer(summary: MultivariateStatisticalSummary): Normalizer = {
      val (mean, stdv) = (summary.mean.toArray, summary.variance.toArray.map(math.sqrt))
      Normalizer(mean, stdv, 0.0, name)
    }
  }
  /**
   * MinMaxCentered scaling: (x - min / (max - min)) * 2 - 1
   */
  case object MinMaxCentered extends NormType("minMaxCentered") {
    def makeNormalizer(summary: MultivariateStatisticalSummary): Normalizer = {
      val (min, max) = (summary.min.toArray, summary.max.toArray)
      val range = max.zip(min).map{ case (mx, mn) => (mx - mn) / 2.0 }
      Normalizer(min, range, 1.0, name)
    }
  }

  // TODO Z norm only for things that are not one hot encoded??
}


case class Normalizer
(
  scaleFactors1: Array[Double],
  scaleFactors2: Array[Double],
  offset: Double,
  name: String
) extends Serializable {

  protected val scaleFactors: Array[(Double, Double)] = scaleFactors1.zip(scaleFactors2)

  def apply(features: OPVector): Array[Double] = {
    features.value.toArray.zip(scaleFactors)
      .map{ case (f, (sf1, sf2)) => if (sf2 == 0.0) 0.0 else (f - sf1) / sf2 - offset }
  }
}