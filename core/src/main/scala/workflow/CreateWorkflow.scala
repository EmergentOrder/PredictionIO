package io.prediction.workflow

import io.prediction.controller.EmptyParams
import io.prediction.controller.EngineParams
import io.prediction.controller.IEngineFactory
import io.prediction.controller.Metrics
import io.prediction.controller.Params
import io.prediction.core.Doer
import io.prediction.core.BaseMetrics
import io.prediction.storage.Run

import com.github.nscala_time.time.Imports._
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import grizzled.slf4j.Logging
import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.native.Serialization.{ read, write }

import scala.io.Source
import scala.language.existentials
import scala.reflect.runtime.universe

import java.io.File

object CreateWorkflow extends Logging {

  case class WorkflowConfig(
    batch: String = "Transient Lazy Val",
    engineId: String = "",
    engineVersion: String = "",
    engineFactory: String = "",
    metricsClass: Option[String] = None,
    dataSourceParamsJsonPath: Option[String] = None,
    preparatorParamsJsonPath: Option[String] = None,
    algorithmsParamsJsonPath: Option[String] = None,
    servingParamsJsonPath: Option[String] = None,
    metricsParamsJsonPath: Option[String] = None,
    jsonBasePath: String = "",
    env: Option[String] = None)

  case class AlgorithmParams(name: String, params: JValue)

  implicit lazy val formats = DefaultFormats
  lazy val gson = new Gson

  private def extractParams(
      mode: String, json: String, clazz: Class[_]): Params = {
    val pClass = clazz.getConstructors.head.getParameterTypes
    if (pClass.size == 0) {
      if (json != "")
        warn(s"Non-empty parameters supplied to ${clazz.getName}, but its " +
          "constructor does not accept any arguments. Stubbing with empty " +
          "parameters.")
      EmptyParams()
    } else {
      val apClass = pClass.head
      mode match {
        case "java" => try {
          gson.fromJson(json, apClass)
        } catch {
          case e: JsonSyntaxException =>
            error(s"Unable to extract parameters for ${apClass.getName} from " +
              s"JSON string: ${json}. Aborting workflow.")
            sys.exit(1)
        }
        case _ => try {
          Extraction.extract(parse(json), reflect.TypeInfo(apClass, None)).
            asInstanceOf[Params]
        } catch {
          case me: MappingException => {
            error(s"Unable to extract parameters for ${apClass.getName} from " +
              s"JSON string: ${json}. Aborting workflow.")
            sys.exit(1)
          }
        }
      }
    }
  }

  private def stringFromFile(basePath: String, filePath: String): String = {
    try {
      if (basePath == "")
        Source.fromFile(filePath).mkString
      else
        Source.fromFile(basePath + File.separator + filePath).mkString
    } catch {
      case e: java.io.FileNotFoundException =>
        error(s"Error reading from file: ${e.getMessage}. Aborting workflow.")
        sys.exit(1)
    }
  }

  def main(args: Array[String]): Unit = {
    val parser = new scopt.OptionParser[WorkflowConfig]("CreateWorkflow") {
      opt[String]("batch") action { (x, c) =>
        c.copy(batch = x)
      } text("Batch label of the workflow run.")
      opt[String]("engineId") required() action { (x, c) =>
        c.copy(engineId = x)
      } text("Engine's ID.")
      opt[String]("engineVersion") required() action { (x, c) =>
        c.copy(engineVersion = x)
      } text("Engine's version.")
      opt[String]("engineFactory") required() action { (x, c) =>
        c.copy(engineFactory = x)
      } text("Class name of the engine's factory.")
      opt[String]("metricsClass") action { (x, c) =>
        c.copy(metricsClass = Some(x))
      } text("Class name of the run's metrics.")
      opt[String]("dsp") action { (x, c) =>
        c.copy(dataSourceParamsJsonPath = Some(x))
      } text("Path to data source parameters JSON file.")
      opt[String]("pp") action { (x, c) =>
        c.copy(preparatorParamsJsonPath = Some(x))
      } text("Path to preparator parameters JSON file.")
      opt[String]("ap") action { (x, c) =>
        c.copy(algorithmsParamsJsonPath = Some(x))
      } text("Path to algorithms parameters JSON file.")
      opt[String]("sp") action { (x, c) =>
        c.copy(servingParamsJsonPath = Some(x))
      } text("Path to serving parameters JSON file.")
      opt[String]("mp") action { (x, c) =>
        c.copy(metricsParamsJsonPath = Some(x))
      } text("Path to metrics parameters")
      opt[String]("jsonBasePath") action { (x, c) =>
        c.copy(jsonBasePath = x)
      } text("Base path to prepend to all parameters JSON files.")
      opt[String]("env") action { (x, c) =>
        c.copy(env = Some(x))
      } text("Comma-separated list of environmental variables (in 'FOO=BAR' " +
        "format) to pass to the Spark execution environment.")
    }

    parser.parse(args, WorkflowConfig()) map { wfc =>
      val runtimeMirror = universe.runtimeMirror(getClass.getClassLoader)
      val engineModule = runtimeMirror.staticModule(wfc.engineFactory)
      val engineObject = runtimeMirror.reflectModule(engineModule)
      var engineRunMode = "scala"
      val engine = try {
        engineObject.instance.asInstanceOf[IEngineFactory]()
      } catch {
        case e @ (_: NoSuchFieldException | _: ClassNotFoundException) => try {
          engineRunMode = "java"
          Class.forName(wfc.engineFactory).newInstance.asInstanceOf[IEngineFactory]()
        } catch {
          case e: ClassNotFoundException =>
            error(s"${e.getMessage}")
            sys.exit(1)
          case e: NoSuchMethodException =>
            error(s"${e.getMessage}")
            sys.exit(1)
        }
      }
      val metrics = wfc.metricsClass.map { mc => //mc => null
        try {
          Class.forName(mc)
            .asInstanceOf[Class[BaseMetrics[_ <: Params, _, _, _, _, _, _, _ <: AnyRef]]]
        } catch {
          case e: ClassNotFoundException =>
            error("Unable to obtain metrics class object ${mc}: " +
              s"${e.getMessage}. Aborting workflow.")
            sys.exit(1)
        }
      }
      val dataSourceParams = wfc.dataSourceParamsJsonPath.map(p =>
        extractParams(
          engineRunMode,
          stringFromFile(wfc.jsonBasePath, p),
          engine.dataSourceClass)).getOrElse(EmptyParams())
      val preparatorParams = wfc.preparatorParamsJsonPath.map(p =>
        extractParams(
          engineRunMode,
          stringFromFile(wfc.jsonBasePath, p),
          engine.preparatorClass)).getOrElse(EmptyParams())
      val algorithmsParams: Seq[(String, Params)] =
        wfc.algorithmsParamsJsonPath.map { p =>
          val algorithmsParamsJson = parse(stringFromFile(wfc.jsonBasePath, p))
          algorithmsParamsJson match {
            case JArray(s) => s.map { algorithmParamsJValue =>
              val eap = algorithmParamsJValue.extract[AlgorithmParams]
              (
                eap.name,
                extractParams(
                  engineRunMode,
                  compact(render(eap.params)),
                  engine.algorithmClassMap(eap.name))
              )
            }
            case _ => Nil
          }
        } getOrElse Seq(("", EmptyParams()))
      val servingParams = wfc.servingParamsJsonPath.map(p =>
        extractParams(
          engineRunMode,
          stringFromFile(wfc.jsonBasePath, p),
          engine.servingClass)).getOrElse(EmptyParams())
      val metricsParams = wfc.metricsParamsJsonPath.map(p =>
        if (metrics.isEmpty)
          EmptyParams()
        else
          extractParams(
            engineRunMode,
            stringFromFile(wfc.jsonBasePath, p),
            metrics.get)
      ) getOrElse EmptyParams()

      val engineParams = new EngineParams(
        dataSourceParams = dataSourceParams,
        preparatorParams = preparatorParams,
        algorithmParamsList = algorithmsParams,
        servingParams = servingParams)

      val metricsInstance = metrics
        .map(m => Doer(m, metricsParams))
        .getOrElse(null)

      val pioEnvVars = wfc.env.map(e =>
        e.split(',').flatMap(p =>
          p.split('=') match {
            case Array(k, v) => List(k -> v)
            case _ => Nil
          }
        ).toMap
      ).getOrElse(Map())

      val run = Run(
        id = "",
        startTime = DateTime.now,
        endTime = DateTime.now,
        engineId = wfc.engineId,
        engineVersion = wfc.engineVersion,
        engineFactory = wfc.engineFactory,
        metricsClass = wfc.metricsClass.getOrElse(""),
        batch = wfc.batch,
        env = pioEnvVars,
        dataSourceParams = write(dataSourceParams),
        preparatorParams = write(preparatorParams),
        algorithmsParams = write(algorithmsParams),
        servingParams = write(servingParams),
        metricsParams = write(metricsParams),
        models = Array[Byte](),
        multipleMetricsResults = "")

      APIDebugWorkflow.runEngineTypeless(
        batch = wfc.batch,
        env = pioEnvVars,
        verbose = 3,
        engine = engine,
        engineParams = engineParams,
        metrics = metricsInstance,
        metricsParams = metricsParams,
        run = Some(run))
    }
  }
}