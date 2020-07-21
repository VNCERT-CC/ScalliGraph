package org.thp.scalligraph.macros

import org.thp.scalligraph.models.Model

import scala.reflect.macros.blackbox

class ModelMacro(val c: blackbox.Context) extends MappingMacroHelper with IndexMacro with MacroLogger {

  import c.universe._

  def getModel[E <: Product: WeakTypeTag]: Tree = {
    val companion = weakTypeOf[E].typeSymbol.companion
    q"$companion.model"
  }

  /**
    * Create a model from entity type e
    */
  def mkVertexModel[E <: Product: WeakTypeTag]: Expr[Model.Vertex[E]] = {
    val entityType: Type = weakTypeOf[E]
    initLogger(entityType.typeSymbol)
    debug(s"Building vertex model for $entityType")
    val label: String      = entityType.toString.split("\\.").last
    val mappings           = getEntityMappings[E]
    val mappingDefinitions = mappings.map(m => q"val ${m.valName} = ${m.definition}")
    val fieldMap           = mappings.map(m => q"${m.name} -> ${m.valName}")
    val setProperties      = mappings.map(m => q"${m.valName}.setProperty(vertex, ${m.name}, e.${TermName(m.name)})")
    val initialValues =
      try {
        val entityTypeModule = entityType.typeSymbol.companion
        if (entityTypeModule.typeSignature.members.exists(_.name.toString == "initialValues"))
          q"$entityTypeModule.initialValues"
        else q"Nil"
      } catch {
        case e: Throwable =>
          error(s"Unable to get initialValues from $label: $e")
          q"Nil"
      }
    val domainBuilder = mappings.map { m =>
      q"""
        try {
          ${m.valName}.getProperty(element, ${m.name})
        } catch {
          case t: Throwable ⇒
            throw InternalError($label + " " + element.id + " doesn't comply with its schema, field `" + ${m.name} + "` is missing (" + element.value(${m.name}) + "): " + Model.printElement(element), t)
        }
        """
    }
    val model = c.Expr[Model.Vertex[E]](q"""
      import java.util.Date
      import scala.concurrent.{ExecutionContext, Future}
      import gremlin.scala.{Graph, Vertex}
      import scala.util.{Failure, Try}
      import org.thp.scalligraph.InternalError
      import org.thp.scalligraph.controllers.FPath
      import org.thp.scalligraph.models.{Database, Entity, IndexType, Mapping, Model, UniMapping, VertexModel}
      import org.thp.scalligraph.steps.Converter

      new VertexModel { thisModel ⇒
        override type E = $entityType

        override val label: String = $label

        override val initialValues: Seq[E] = $initialValues
        override val indexes: Seq[(IndexType.Value, Seq[String])] = ${getIndexes[E]}

        ..$mappingDefinitions

        override def create(e: E)(implicit db: Database, graph: Graph): Vertex = {
          val vertex = graph.addVertex(label)
          ..$setProperties
          vertex
        }

        override val fields: Map[String, Mapping[_, _, _]] = Map(..$fieldMap)

        override val converter: Converter[EEntity, ElementType] = (element: ElementType) => new $entityType(..$domainBuilder) with Entity {
          val _id        = element.id().toString
          val _model     = thisModel
          val _createdAt = UniMapping.date.getProperty(element, "_createdAt")
          val _createdBy = UniMapping.string.getProperty(element, "_createdBy")
          val _updatedAt = UniMapping.date.optional.getProperty(element, "_updatedAt")
          val _updatedBy = UniMapping.string.optional.getProperty(element, "_updatedBy")
        }

        override def addEntity(e: $entityType, entity: Entity): EEntity = new $entityType(..${mappings
      .map(m => q"e.${TermName(m.name)}")}) with Entity {
          override def _id: String                = entity._id
          override def _model: Model              = entity._model
          override def _createdBy: String         = entity._createdBy
          override def _updatedBy: Option[String] = entity._updatedBy
          override def _createdAt: Date           = entity._createdAt
          override def _updatedAt: Option[Date]   = entity._updatedAt
        }
      }
      """)
    ret(s"Vertex model $entityType", model)
  }

  def mkEdgeModel[E <: Product: WeakTypeTag, FROM <: Product: WeakTypeTag, TO <: Product: WeakTypeTag]: Expr[Model.Edge[E, FROM, TO]] = {
    val entityType: Type = weakTypeOf[E]
    val fromType: Type   = weakTypeOf[FROM]
    val toType: Type     = weakTypeOf[TO]
    initLogger(entityType.typeSymbol)
    debug(s"Building vertex model for $entityType")
    val label: String      = entityType.toString.split("\\.").last
    val fromLabel: String  = fromType.toString.split("\\.").last
    val toLabel: String    = toType.toString.split("\\.").last
    val mappings           = getEntityMappings[E]
    val mappingDefinitions = mappings.map(m => q"val ${m.valName} = ${m.definition}")
    val fieldMap           = mappings.map(m => q"${m.name} -> ${m.valName}")
    val setProperties      = mappings.map(m => q"${m.valName}.setProperty(edge, ${m.name}, e.${TermName(m.name)})")
    val getProperties      = mappings.map(m => q"${m.valName}.getProperty(edge, ${m.name})")
    val model              = c.Expr[Model.Edge[E, FROM, TO]](q"""
      import java.util.Date
      import scala.concurrent.{ExecutionContext, Future}
      import scala.util.Try
      import gremlin.scala.{Edge, Graph, Vertex}
      import org.thp.scalligraph.InternalError
      import org.thp.scalligraph.controllers.FPath
      import org.thp.scalligraph.models.{Database, EdgeModel, Entity, IndexType, Mapping, MappingCardinality, Model, UniMapping}

      new EdgeModel[$fromType, $toType] { thisModel ⇒
        override type E = $entityType

        override val label: String = $label

        override val fromLabel: String = $fromLabel

        override val toLabel: String = $toLabel

        override val indexes: Seq[(IndexType.Value, Seq[String])] = ${getIndexes[E]}

        ..$mappingDefinitions

        override def create(e: E, from: Vertex, to: Vertex)(implicit db: Database, graph: Graph): Edge = {
          val edge = from.addEdge(label, to)
          ..$setProperties
          edge
        }

        override val fields: Map[String, Mapping[_, _, _]] = Map(..$fieldMap)

        override val converter: Converter[EEntity, ElementType] = (edge: ElementType) => new $entityType(..$getProperties) with Entity {
          val _id        = edge.id().toString
          val _model     = thisModel
          val _createdAt = UniMapping.date.getProperty(edge, "_createdAt")
          val _createdBy = UniMapping.string.getProperty(edge, "_createdBy")
          val _updatedAt = UniMapping.date.optional.getProperty(edge, "_updatedAt")
          val _updatedBy = UniMapping.string.optional.getProperty(edge, "_updatedBy")
        }

        override def addEntity(e: $entityType, entity: Entity): EEntity =
          new $entityType(..${mappings.map(m => q"e.${TermName(m.name)}")}) with Entity {
            override def _id: String                = entity._id
            override def _model: Model              = entity._model
            override def _createdBy: String         = entity._createdBy
            override def _updatedBy: Option[String] = entity._updatedBy
            override def _createdAt: Date           = entity._createdAt
            override def _updatedAt: Option[Date]   = entity._updatedAt
          }
      }
      """)
    ret(s"Edge model $entityType", model)
  }
}
