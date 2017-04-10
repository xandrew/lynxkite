// Methods for manipulating workspaces.
package com.lynxanalytics.biggraph.controllers

import scala.collection.mutable.HashMap
import com.lynxanalytics.biggraph.SparkFreeEnvironment
import com.lynxanalytics.biggraph.frontend_operations.Operations
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_util.Timestamp
import com.lynxanalytics.biggraph.serving

case class GetWorkspaceRequest(name: String)
case class SetWorkspaceRequest(name: String, workspace: Workspace)
case class GetSummaryRequest(operationId: String, parameters: Map[String, String])
case class GetSummaryResponse(summary: String)
case class GetOutputIDRequest(workspace: String, output: BoxOutput)
case class GetProjectOutputRequest(id: String, path: String)
case class GetProgressRequest(workspace: String, output: BoxOutput)
case class GetOperationMetaRequest(workspace: String, box: String)
case class GetOutputIDResponse(id: String, kind: String)
case class GetOutputResponse(kind: String, project: Option[FEProject] = None)
case class GetProgressResponse(progressList: List[BoxOutputProgress])
case class CreateWorkspaceRequest(name: String, privacy: String)
case class BoxCatalogResponse(boxes: List[BoxMetadata])

class WorkspaceController(env: SparkFreeEnvironment) {
  implicit val metaManager = env.metaGraphManager
  implicit val entityProgressManager: EntityProgressManager = env.entityProgressManager

  val ops = new Operations(env)

  private def assertNameNotExists(name: String) = {
    assert(!DirectoryEntry.fromName(name).exists, s"Entry '$name' already exists.")
  }

  def createWorkspace(
    user: serving.User, request: CreateWorkspaceRequest): Unit = metaManager.synchronized {
    assertNameNotExists(request.name)
    val entry = DirectoryEntry.fromName(request.name)
    entry.assertParentWriteAllowedFrom(user)
    val w = entry.asNewWorkspaceFrame()
    w.setupACL(request.privacy, user)
  }

  private def getWorkspaceByName(
    user: serving.User, name: String): Workspace = metaManager.synchronized {
    val f = DirectoryEntry.fromName(name)
    assert(f.exists, s"Project ${name} does not exist.")
    f.assertReadAllowedFrom(user)
    f match {
      case f: WorkspaceFrame => f.workspace
      case _ => throw new AssertionError(s"${name} is not a workspace.")
    }
  }

  def getWorkspace(
    user: serving.User, request: GetWorkspaceRequest): Workspace =
    getWorkspaceByName(user, request.name)

  // This is for storing the calculated BoxOutputState objects, so the same states can be referenced later.
  val calculatedStates = new HashMap[String, BoxOutputState]()

  def getSummary(
    user: serving.User, request: GetSummaryRequest): GetSummaryResponse = {
    println(request.operationId)
    println(request.parameters)
    GetSummaryResponse("Best summary ever.")
  }

  def getOutputID(
    user: serving.User, request: GetOutputIDRequest): GetOutputIDResponse = {
    val ws = getWorkspaceByName(user, request.workspace)
    val state = ws.state(user, ops, request.output)
    val id = Timestamp.toString
    calculatedStates.synchronized {
      calculatedStates(id) = state
    }
    GetOutputIDResponse(id, state.kind)
  }

  def getProjectOutput(
    user: serving.User, request: GetProjectOutputRequest): FEProject = {
    calculatedStates.synchronized {
      calculatedStates.get(request.id)
    } match {
      case None => throw new AssertionError(s"BoxOutputState state identified by ${request.id} not found")
      case Some(state: BoxOutputState) =>
        state.kind match {
          case BoxOutputKind.Project =>
            val pathSeq = SubProject.splitPipedPath(request.path)
              .filter(_ != "")
            val viewer = state.project.viewer.offspringViewer(pathSeq)
            viewer.toFE(request.path)
        }
    }
  }

  def getProgress(
    user: serving.User, request: GetProgressRequest): GetProgressResponse = {
    val ws = getWorkspaceByName(user, request.workspace)
    GetProgressResponse(ws.progress(user, ops, request.output))
  }

  def setWorkspace(
    user: serving.User, request: SetWorkspaceRequest): Unit = metaManager.synchronized {
    val f = DirectoryEntry.fromName(request.name)
    assert(f.exists, s"Project ${request.name} does not exist.")
    f.assertWriteAllowedFrom(user)
    f match {
      case f: WorkspaceFrame =>
        val cp = request.workspace.checkpoint(previous = f.checkpoint)
        f.setCheckpoint(cp)
      case _ => throw new AssertionError(s"${request.name} is not a workspace.")
    }
  }

  def boxCatalog(user: serving.User, request: serving.Empty): BoxCatalogResponse = {
    val boxmeta = ops.getBoxMetadata("Compute clustering coefficient")
    println(ops.opForBox(user, new Box("", boxmeta.operationID, Map(), 0, 0, Map()), Map()).summary)
    BoxCatalogResponse(ops.operationIds.toList.map(ops.getBoxMetadata(_)))
  }

  def getOperationMeta(user: serving.User, request: GetOperationMetaRequest): FEOperationMeta = {
    val ws = getWorkspaceByName(user, request.workspace)
    val op = ws.getOperation(user, ops, request.box)
    op.toFE
  }
}
