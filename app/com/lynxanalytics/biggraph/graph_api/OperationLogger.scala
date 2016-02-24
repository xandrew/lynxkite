// OperationLogger will log useful performance data about an operation, including information about
// the inputs and the outputs.

package com.lynxanalytics.biggraph.graph_api

import play.api.libs.json
import scala.concurrent.ExecutionContextExecutorService

import com.lynxanalytics.biggraph.{ bigGraphLogger => log }

class OperationLogger(instance: MetaGraphOperationInstance,
                      implicit val ec: ExecutionContextExecutorService) {
  private val marker = "OPERATION_LOGGER_MARKER"
  case class OutputInfo(name: String, gUID: String, partitions: Int, count: Option[Long])
  case class InputInfo(name: String, gUID: String, partitions: Int, count: Option[Long])

  private val outputInfoList = scala.collection.mutable.Queue[SafeFuture[OutputInfo]]()
  private val inputInfoList = scala.collection.mutable.Queue[InputInfo]()
  private var startTime = -1L
  private var stopTime = -1L

  private def elapsedMs(): Long = {
    assert(startTime != -1 && stopTime != -1)
    val diff = stopTime - startTime
    assert(diff >= 0)
    diff
  }

  def addOutput(output: SafeFuture[EntityData]): Unit = {
    outputInfoList += output.map {
      o =>
        val rddData = o.asInstanceOf[EntityRDDData[_]]
        OutputInfo(
          rddData.entity.name.name,
          rddData.entity.gUID.toString,
          rddData.rdd.partitions.size,
          rddData.count)
    }
  }

  def start(): Unit = {
    assert(startTime == -1)
    startTime = System.currentTimeMillis()
  }

  def stop(): Unit = {
    assert(stopTime == -1)
    stopTime = System.currentTimeMillis()
  }
  def addInput(input: EntityData): Unit = {
    if (instance.operation.isHeavy && !input.isInstanceOf[ScalarData[_]]) {
      inputInfoList += {
        val rddData = input.asInstanceOf[EntityRDDData[_]]
        InputInfo(
          rddData.entity.name.name,
          rddData.entity.gUID.toString,
          rddData.rdd.partitions.size,
          rddData.count)
      }
    }
  }

  def logWhenReady(): Unit = {
    val outputsFuture = SafeFuture.sequence(outputInfoList)

    outputsFuture.map {
      outputs => dump(outputs)
    }
  }

  private def dump(outputs: Seq[OutputInfo]): Unit = {

    implicit val formatInput = json.Json.format[InputInfo]
    implicit val formatOutput = json.Json.format[OutputInfo]

    val out = json.Json.obj(
      "name" -> instance.operation.toString,
      "guid" -> instance.operation.gUID.toString,
      "elapsedMs" -> json.JsNumber(elapsedMs()),
      "inputs" -> inputInfoList.sortBy(_.gUID),
      "outputs" -> outputs.sortBy(_.gUID)
    )
    log.info(s"$marker $out")
  }
}
