package com.lynxanalytics.biggraph.graph_api

import com.lynxanalytics.biggraph.graph_util
import play.api.libs.json.Json
import scala.reflect.runtime.universe.typeTag

class SphynxMemory(host: String, port: Int) extends Domain {
  implicit val executionContext =
    ThreadUtil.limitedExecutionContext(
      "SphynxMemory",
      maxParallelism = graph_util.LoggedEnvironment.envOrElse("KITE_PARALLELISM", "5").toInt)

  val client = new SphynxClient(host, port)

  override def has(entity: MetaGraphEntity): Boolean = {
    return false
  }

  override def compute(instance: MetaGraphOperationInstance): SafeFuture[Unit] = {
    val jsonMeta = Json.stringify(MetaGraphManager.serializeOperation(instance))
    val f = client.compute(jsonMeta)
    SafeFuture(f)
  }

  override def canCompute(instance: MetaGraphOperationInstance): Boolean = {
    val jsonMeta = Json.stringify(MetaGraphManager.serializeOperation(instance))
    val res = client.canCompute(jsonMeta)
    println("Got a canCompute response from Sphynx!")
    return res
  }

  override def get[T](scalar: Scalar[T]): SafeFuture[T] = {
    val f = client.getScalar(scalar)
    SafeFuture(f)
  }

  override def cache(e: MetaGraphEntity): Unit = {
    ???
  }

  override def relocate(e: MetaGraphEntity, source: Domain): SafeFuture[Unit] = {
    ???
  }

}
