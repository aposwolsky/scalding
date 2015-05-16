/*
Copyright 2014 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.twitter.scalding

import cascading.flow.hadoop.HadoopFlow
import cascading.flow.planner.BaseFlowStep
import cascading.flow.{ FlowStep, FlowStepStrategy, FlowDef, Flow }
import cascading.pipe.Pipe
import com.twitter.scalding.reducer_estimation.ReducerEstimatorStepStrategy
import java.util.{ List => JList }
import org.apache.hadoop.mapred.JobConf
import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

/*
 * This has all the state needed to build a single flow
 * This is used with the implicit-arg-as-dependency-injection
 * style and with the Reader-as-dependency-injection
 */
trait ExecutionContext {
  def config: Config
  def flowDef: FlowDef
  def mode: Mode

  private def getIdentifierOpt(descriptions: Seq[String]): Option[String] = {
    if (descriptions.nonEmpty) Some(descriptions.distinct.mkString(", ")) else None
  }

  private def updateFlowStepName(step: BaseFlowStep[JobConf], descriptions: Seq[String]): BaseFlowStep[JobConf] = {
    getIdentifierOpt(descriptions).foreach(newIdentifier => {
      val stepXofYData = """\(\d+/\d+\)""".r.findFirstIn(step.getName).getOrElse("")
      // Reflection is only temporary.  Latest cascading has setName public: https://github.com/cwensel/cascading/commit/487a6e9ef#diff-0feab84bc8832b2a39312dbd208e3e69L175
      // https://github.com/twitter/scalding/issues/1294
      val x = classOf[BaseFlowStep[JobConf]].getDeclaredMethod("setName", classOf[String])
      x.setAccessible(true)
      x.invoke(step, "%s %s".format(stepXofYData, newIdentifier))
    })
    step
  }

  private def getDesc(baseFlowStep: BaseFlowStep[JobConf]): Seq[String] = {
    baseFlowStep.getGraph.vertexSet.asScala.toSeq.flatMap(_ match {
      case pipe: Pipe => RichPipe.getPipeDescriptions(pipe)
      case _ => List() // no descriptions
    })
  }

  object WithDescriptionStepStrategy extends FlowStepStrategy[JobConf] {
    override def apply(
      flow: Flow[JobConf],
      preds: JList[FlowStep[JobConf]],
      step: FlowStep[JobConf]): Unit = {

      val conf = step.getConfig
      val baseFlowStep: BaseFlowStep[JobConf] = step.asInstanceOf[BaseFlowStep[JobConf]]
      getIdentifierOpt(getDesc(baseFlowStep)).foreach(id => {
        val newJobName = "%s %s".format(conf.getJobName, id)
        println("Added descriptions to job name: %s".format(newJobName))
        conf.setJobName(newJobName)
      })
    }
  }

  final def buildFlow: Try[Flow[_]] =
    // For some horrible reason, using Try( ) instead of the below gets me stuck:
    // [error]
    // /Users/oscar/workspace/scalding/scalding-core/src/main/scala/com/twitter/scalding/Execution.scala:92:
    // type mismatch;
    // [error]  found   : cascading.flow.Flow[_]
    // [error]  required: cascading.flow.Flow[?0(in method buildFlow)] where type ?0(in method
    //   buildFlow)
    // [error] Note: Any >: ?0, but Java-defined trait Flow is invariant in type Config.
    // [error] You may wish to investigate a wildcard type such as `_ >: ?0`. (SLS 3.2.10)
    // [error]       (resultT, Try(mode.newFlowConnector(finalConf).connect(newFlowDef)))
    try {
      // identify the flowDef
      val withId = config.addUniqueId(UniqueID.getIDFor(flowDef))
      val flow = mode.newFlowConnector(withId).connect(flowDef)

      flow match {
        case hadoopFlow: HadoopFlow =>
          val flowSteps = hadoopFlow.getFlowSteps.asScala
          flowSteps.foreach(step => {
            val baseFlowStep: BaseFlowStep[JobConf] = step.asInstanceOf[BaseFlowStep[JobConf]]
            updateFlowStepName(baseFlowStep, getDesc(baseFlowStep))
          })
        case _ => // descriptions not yet supported in other modes
      }

      // if any reducer estimators have been set, register the step strategy
      // which instantiates and runs them
      mode match {
        case _: HadoopMode =>
          if (config.get(Config.ReducerEstimators).isDefined)
            flow.setFlowStepStrategy(FlowStepStrategies.plus(WithDescriptionStepStrategy, ReducerEstimatorStepStrategy))
          else
            flow.setFlowStepStrategy(WithDescriptionStepStrategy)

        case _ => ()
      }

      Success(flow)
    } catch {
      case err: Throwable => Failure(err)
    }

  /**
   * Asynchronously execute the plan currently
   * contained in the FlowDef
   */
  final def run: Future[JobStats] =
    buildFlow match {
      case Success(flow) => Execution.run(flow)
      case Failure(err) => Future.failed(err)
    }

  /**
   * Synchronously execute the plan in the FlowDef
   */
  final def waitFor: Try[JobStats] =
    buildFlow.flatMap(Execution.waitFor(_))
}

/*
 * import ExecutionContext._
 * is generally needed to use the ExecutionContext as the single
 * dependency injected. For instance, TypedPipe needs FlowDef and Mode
 * in many cases, so if you have an implicit ExecutionContext, you need
 * modeFromImplicit, etc... below.
 */
object ExecutionContext {
  /*
   * implicit val ec = ExecutionContext.newContext(config)
   * can be used inside of a Job to get an ExecutionContext if you want
   * to call a function that requires an implicit ExecutionContext
   */
  def newContext(conf: Config)(implicit fd: FlowDef, m: Mode): ExecutionContext =
    new ExecutionContext {
      def config = conf
      def flowDef = fd
      def mode = m
    }

  /*
   * Creates a new ExecutionContext, with an empty FlowDef, given the Config and the Mode
   */
  def newContextEmpty(conf: Config, md: Mode): ExecutionContext = {
    val newFlowDef = new FlowDef
    conf.getCascadingAppName.foreach(newFlowDef.setName)
    newContext(conf)(newFlowDef, md)
  }

  implicit def modeFromContext(implicit ec: ExecutionContext): Mode = ec.mode
  implicit def flowDefFromContext(implicit ec: ExecutionContext): FlowDef = ec.flowDef
}

