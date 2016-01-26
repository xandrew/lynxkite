// Package-level variables. Creates our logger and the BigGraphEnvironment.
package com.lynxanalytics

import com.lynxanalytics.biggraph.graph_util.PrefixRepository
import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory
import scala.reflect.runtime.universe._

package object biggraph {
  val bigGraphLogger = LoggerFactory.getLogger("LynxKite")

  // Initialize reflection to avoid thread-safety issues
  // TODO: ditch this when we get to Scala 2.11
  def printType[T: TypeTag]: Unit = bigGraphLogger.debug("initialize reflection for type: " + typeOf[T])

  printType[Long]
  printType[String]
  printType[Double]
  printType[Array[Long]]

  // static<meta_dir,data_dir,ephemeral_data_dir>
  private val staticRepoPattern = "static<(.+),(.+),(.*)>".r

  val standardDataPrefix = "DATA$"

  def registerStandardPrefixes() = {
    PrefixRepository.registerPrefix("UPLOAD$", standardDataPrefix + "/uploads")
  }

  lazy val BigGraphProductionEnvironment: BigGraphEnvironment = {
    // Make sure play and spark logs contain the proper context.
    val ctx = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val frameworkPackages = ctx.getFrameworkPackages
    frameworkPackages.add("play.api.Logger")
    frameworkPackages.add("org.apache.spark.Logging")

    bigGraphLogger.info("Starting to initialize production Kite environment")
    val repoDirs =
      scala.util.Properties.envOrNone("REPOSITORY_MODE") match {
        case Some(staticRepoPattern(metaDir, dataDir, "")) =>
          new RepositoryDirs(metaDir, standardDataPrefix, dataDir)
        case Some(staticRepoPattern(metaDir, dataDir, ephemeralDataDir)) =>
          new RepositoryDirs(metaDir, standardDataPrefix, dataDir, Some(ephemeralDataDir))
        case Some(rm) =>
          throw new AssertionError(s"Could not parse REPOSITORY_MODE ($rm)")
        case None =>
          throw new AssertionError("REPOSITORY_MODE is not defined")
      }
    repoDirs.forcePrefixRegistration()
    registerStandardPrefixes()

    val res = BigGraphEnvironmentImpl.createStaticDirEnvironment(
      repoDirs,
      new StaticSparkContextProvider())
    bigGraphLogger.info("Production Kite environment initialized")
    res
  }
}
