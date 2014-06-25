package com.lynxanalytics.biggraph.controllers

import org.apache.spark.SparkContext.rddToPairRDDFunctions

import com.lynxanalytics.biggraph.BigGraphEnvironment
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_api.MetaGraphManager.StringAsUUID
import com.lynxanalytics.biggraph.graph_operations
import com.lynxanalytics.biggraph.graph_util

case class VertexAttributeFilter(
  val attributeId: String,
  val valueSpec: String)
case class VertexDiagramSpec(
  val vertexSetId: String,
  val filters: Seq[VertexAttributeFilter],
  val mode: String, // For now, one of "bucketed", "sampled".

  // ** Parameters for bucketed view **
  // Empty string means no bucketing on that axis.
  val xBucketingAttributeId: String = "",
  val xNumBuckets: Int = 1,
  val yBucketingAttributeId: String = "",
  val yNumBuckets: Int = 1,

  // ** Parameters for sampled view **
  // Empty string means auto select randomly.
  val centralVertexId: String = "",
  // Edge bundle used to find neighborhood of the central vertex.
  val sampleSmearEdgeBundleId: String = "",
  val radius: Int = 1)

case class FEVertex(
  x: Int,
  y: Int,
  count: Int)
case class VertexDiagramResponse(
  val diagramId: String,
  val vertices: Seq[FEVertex],
  val mode: String, // as specified in the request

  // ** Only set for bucketed view **
  val xBuckets: Seq[String] = Seq(),
  val yBuckets: Seq[String] = Seq())

case class EdgeDiagramSpec(
  // In the context of an FEGraphRequest "idx[4]" means the diagram requested by vertexSets(4).
  // Otherwise a UUID obtained by a previous vertex diagram request.
  val srcDiagramId: String,
  val dstDiagramId: String,
  // These are copied verbatim to the response, used by the FE to identify EdgeDiagrams.
  val srcIdx: Int,
  val dstIdx: Int,
  val bundleIdSequence: Seq[String])

case class FEEdge(
  // idx of source vertex in the vertices Seq in the corresponding VertexDiagramResponse.
  a: Int,
  // idx of destination vertex in the vertices Seq in the corresponding VertexDiagramResponse.
  b: Int,
  count: Int)

case class EdgeDiagramResponse(
  val srcDiagramId: String,
  val dstDiagramId: String,

  // Copied from the request.
  val srcIdx: Int,
  val dstIdx: Int,

  val edges: Seq[FEEdge])

case class FEGraphRequest(
  vertexSets: Seq[VertexDiagramSpec],
  edgeBundles: Seq[EdgeDiagramSpec])

case class FEGraphRespone(
  vertexSets: Seq[VertexDiagramResponse],
  edgeBundles: Seq[EdgeDiagramResponse])

class GraphDrawingController(env: BigGraphEnvironment) {
  val metaManager = env.metaGraphManager
  val dataManager = env.dataManager

  def sampleAttribute(sampled: VertexSet,
                      attribute: VertexAttribute[Double]): VertexAttribute[Double] =
    metaManager.apply(
      graph_operations.SampledDoubleVertexAttribute(),
      'attribute -> attribute,
      'sampled -> sampled).outputs.vertexAttributes('sampled_attribute).runtimeSafeCast[Double]

  def getVertexDiagram(request: VertexDiagramSpec): VertexDiagramResponse = {
    if (request.mode != "bucketed") return ???
    val vertexSet = metaManager.vertexSet(request.vertexSetId.asUUID)
    val count = graph_operations.CountVertices(metaManager, dataManager, vertexSet)
    // TODO get from request or something.
    val targetSample = 10000
    val sampled =
      if (count <= targetSample) vertexSet
      else metaManager.apply(
        graph_operations.VertexSample(targetSample * 1.0 / count),
        'vertices -> vertexSet).outputs.vertexSets('sampled)

    var (xMin, xMax, yMin, yMax) = (-1.0, -1.0, -1.0, -1.0)
    var inputs = MetaDataSet(Map('vertices -> sampled))
    if (request.xNumBuckets > 1 && request.xBucketingAttributeId.nonEmpty) {
      val attribute =
        metaManager.vertexAttribute(request.xBucketingAttributeId.asUUID).runtimeSafeCast[Double]
      val (min, max) = graph_operations.ComputeMinMax(metaManager, dataManager, attribute)
      xMin = min
      xMax = max
      inputs ++= MetaDataSet(Map('xAttribute -> sampleAttribute(sampled, attribute)))
    }
    if (request.yNumBuckets > 1 && request.yBucketingAttributeId.nonEmpty) {
      val attribute =
        metaManager.vertexAttribute(request.yBucketingAttributeId.asUUID).runtimeSafeCast[Double]
      val (min, max) = graph_operations.ComputeMinMax(metaManager, dataManager, attribute)
      yMin = min
      yMax = max
      inputs ++= MetaDataSet(Map('yAttribute -> sampleAttribute(sampled, attribute)))
    }
    val op = graph_operations.VertexBucketGrid(
      request.xNumBuckets, request.yNumBuckets, xMin, xMax, yMin, yMax)
    val diagramMeta = metaManager.apply(op, inputs)
      .outputs.scalars('bucketSizes).runtimeSafeCast[Map[(Int, Int), Int]]
    val diagram = dataManager.get(diagramMeta).value

    val vertices = for (x <- (0 to request.xNumBuckets); y <- (0 to request.yNumBuckets))
      yield FEVertex(x, y, diagram.getOrElse((x, y), 0))

    VertexDiagramResponse(
      diagramId = diagramMeta.gUID.toString,
      vertices = vertices,
      mode = "bucketed",
      xBuckets = op.xBucketLabels,
      yBuckets = op.yBucketLabels)
  }

  private def getCompositeBundle(bundleIds: Seq[String]): EdgeAttribute[Double] = {
    val bundles = bundleIds.map(id => metaManager.edgeBundle(id.asUUID))
    val weights = bundles.map(bundle =>
      metaManager
        .apply(graph_operations.AddConstantDoubleEdgeAttribute(1),
          'edges -> bundle)
        .outputs
        .edgeAttributes('attr).runtimeSafeCast[Double])
    return new graph_util.BundleChain(weights).getCompositeEdgeBundle(metaManager)
  }

  private def vsFromOp(inst: MetaGraphOperationInstance): VertexSet =
    inst.inputs.vertexSets('vertices)

  private def inducedBundle(eb: EdgeBundle,
                            src: VertexSet,
                            dst: VertexSet): EdgeBundle =
    metaManager.apply(
      new graph_operations.InducedEdgeBundle(),
      'input -> eb,
      'srcSubset -> src,
      'dstSubset -> dst).outputs.edgeBundles('induced)

  private def tripletMapping(eb: EdgeBundle): (VertexAttribute[Array[ID]], VertexAttribute[Array[ID]]) = {
    val metaOut = metaManager.apply(
      graph_operations.TripletMapping(),
      'input -> eb).outputs
    return (
      metaOut.vertexAttributes('srcEdges).runtimeSafeCast[Array[ID]],
      metaOut.vertexAttributes('dstEdges).runtimeSafeCast[Array[ID]])
  }

  private def idxsFromInst(inst: MetaGraphOperationInstance): VertexAttribute[Int] =
    inst.outputs.vertexAttributes('gridIdxs).runtimeSafeCast[Int]

  private def numYBuckets(inst: MetaGraphOperationInstance): Int = {
    inst.operation.asInstanceOf[graph_operations.VertexBucketGrid].ySize
  }

  private def mappedAttribute(mapping: VertexAttribute[Array[ID]],
                              attr: VertexAttribute[Int],
                              target: EdgeBundle): EdgeAttribute[Int] =
    metaManager.apply(
      new graph_operations.VertexToEdgeIntAttribute(),
      'mapping -> mapping,
      'original -> attr,
      'target -> target).outputs.edgeAttributes('mapped_attribute).runtimeSafeCast[Int]

  def getEdgeDiagram(request: EdgeDiagramSpec): EdgeDiagramResponse = {
    val srcOp = metaManager.scalar(request.srcDiagramId.asUUID).source
    val dstOp = metaManager.scalar(request.dstDiagramId.asUUID).source
    val bundleWeights = getCompositeBundle(request.bundleIdSequence)
    val induced = inducedBundle(bundleWeights.edgeBundle, vsFromOp(srcOp), vsFromOp(dstOp))
    val (srcMapping, dstMapping) = tripletMapping(induced)
    val srcIdxs = mappedAttribute(srcMapping, idxsFromInst(srcOp), induced)
    val dstIdxs = mappedAttribute(dstMapping, idxsFromInst(dstOp), induced)
    val srcIdxsRDD = dataManager.get(srcIdxs).rdd
    val dstIdxsRDD = dataManager.get(dstIdxs).rdd
    val idxPairBuckets = srcIdxsRDD.join(dstIdxsRDD)
      .map { case (eid, (s, d)) => ((s, d), 1) }
      .reduceByKey(_ + _)
      .collect
    EdgeDiagramResponse(
      request.srcDiagramId,
      request.dstDiagramId,
      request.srcIdx,
      request.dstIdx,
      idxPairBuckets.map { case ((s, d), c) => FEEdge(s, d, c) })
  }

  def getComplexView(request: FEGraphRequest): FEGraphRespone = {
    val vertexDiagrams = request.vertexSets.map(getVertexDiagram(_))
    val idxPattern = "idx\\[(\\d+)\\]".r
    def resolveDiagramId(reference: String): String = {
      reference match {
        case idxPattern(idx) => vertexDiagrams(idx.toInt).diagramId
        case id: String => id
      }
    }
    val modifiedEdgeSpecs = request.edgeBundles
      .map(eb => eb.copy(
        srcDiagramId = resolveDiagramId(eb.srcDiagramId),
        dstDiagramId = resolveDiagramId(eb.dstDiagramId)))
    val edgeDiagrams = modifiedEdgeSpecs.map(getEdgeDiagram(_))
    return FEGraphRespone(vertexDiagrams, edgeDiagrams)
  }
}
