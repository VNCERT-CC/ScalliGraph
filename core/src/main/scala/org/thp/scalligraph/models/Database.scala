package org.thp.scalligraph.models

import java.io.InputStream
import java.lang.reflect.Modifier
import java.util.{Base64, Date, UUID}

import scala.collection.JavaConverters._
import scala.reflect.runtime.{universe ⇒ ru}
import scala.reflect.{classTag, ClassTag}

import play.api.Logger

import gremlin.scala.{Graph, _}
import org.apache.tinkerpop.gremlin.structure.VertexProperty.Cardinality
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.controllers.UpdateOps
import org.thp.scalligraph.services.{EdgeSrv, VertexSrv}
import org.thp.scalligraph.{FPath, InternalError}

trait Database {
  def noTransaction[A](body: Graph ⇒ A): A
  def transaction[A](body: Graph ⇒ A): A

  def version: Int
  def setVersion(v: Int): Unit

  def getModel[E <: Product: ru.TypeTag]: Model.Base[E]
  def getVertexModel[E <: Product: ru.TypeTag]: Model.Vertex[E]
  def getEdgeModel[E <: Product: ru.TypeTag, FROM <: Product, TO <: Product]: Model.Edge[E, FROM, TO]

  def createSchemaFrom(schemaObject: Any)(implicit authContext: AuthContext): Unit
  def createSchema(model: Model, models: Model*): Unit = createSchema(model +: models)
  def createSchema(models: Seq[Model]): Unit
  def createSchema(models: Seq[Model], vertexSrvs: Seq[VertexSrv[_, _]], edgeSrvs: Seq[EdgeSrv[_, _, _]])(implicit authContext: AuthContext): Unit

  def createVertex[V <: Product](graph: Graph, authContext: AuthContext, model: Model.Vertex[V], v: V): V with Entity
  def createEdge[E <: Product, FROM <: Product, TO <: Product](
      graph: Graph,
      authContext: AuthContext,
      model: Model.Edge[E, FROM, TO],
      e: E,
      from: FROM with Entity,
      to: TO with Entity): E with Entity

  def update(graph: Graph, authContext: AuthContext, model: Model, id: String, fields: Map[FPath, UpdateOps.Type]): Unit

  def getSingleProperty[D, G](element: Element, key: String, mapping: SingleMapping[D, G]): D
  def getOptionProperty[D, G](element: Element, key: String, mapping: OptionMapping[D, G]): Option[D]
  def getListProperty[D, G](element: Element, key: String, mapping: ListMapping[D, G]): Seq[D]
  def getSetProperty[D, G](element: Element, key: String, mapping: SetMapping[D, G]): Set[D]
  def getProperty[D](element: Element, key: String, mapping: Mapping[D, _, _]): D =
    mapping match {
      case m: SingleMapping[_, _] ⇒ getSingleProperty(element, key, m).asInstanceOf[D]
      case m: OptionMapping[_, _] ⇒ getOptionProperty(element, key, m).asInstanceOf[D]
      case m: ListMapping[_, _]   ⇒ getListProperty(element, key, m).asInstanceOf[D]
      case m: SetMapping[_, _]    ⇒ getSetProperty(element, key, m).asInstanceOf[D]
    }

  def setSingleProperty[D, G](element: Element, key: String, value: D, mapping: SingleMapping[D, _]): Unit
  def setOptionProperty[D, G](element: Element, key: String, value: Option[D], mapping: OptionMapping[D, _]): Unit
  def setListProperty[D, G](element: Element, key: String, values: Seq[D], mapping: ListMapping[D, _]): Unit
  def setSetProperty[D, G](element: Element, key: String, values: Set[D], mapping: SetMapping[D, _]): Unit
  def setProperty[D](element: Element, key: String, value: D, mapping: Mapping[D, _, _]): Unit =
    mapping match {
      case m: SingleMapping[d, _] ⇒ setSingleProperty(element, key, value, m)
      case m: OptionMapping[d, _] ⇒ setOptionProperty(element, key, value.asInstanceOf[Option[d]], m)
      case m: ListMapping[d, _]   ⇒ setListProperty(element, key, value.asInstanceOf[Seq[d]], m)
      case m: SetMapping[d, _]    ⇒ setSetProperty(element, key, value.asInstanceOf[Set[d]], m)

    }

  def loadBinary(initialVertex: Vertex)(implicit graph: Graph): InputStream
  def loadBinary(id: String)(implicit graph: Graph): InputStream
  def saveBinary(is: InputStream)(implicit graph: Graph): Vertex
}

abstract class BaseDatabase extends Database {
  lazy val logger = Logger("org.thp.scalligraph.models.Database")

  val idMapping: SingleMapping[UUID, String]          = SingleMapping[UUID, String](uuid ⇒ Some(uuid.toString), UUID.fromString)
  val createdAtMapping: SingleMapping[Date, Date]     = UniMapping.dateMapping
  val createdByMapping: SingleMapping[String, String] = UniMapping.stringMapping
  val updatedAtMapping: OptionMapping[Date, Date]     = UniMapping.dateMapping.optional
  val updatedByMapping: OptionMapping[String, String] = UniMapping.stringMapping.optional

  override def version: Int = noTransaction(_.variables.get[Int]("version").orElse(0))

  override def setVersion(v: Int): Unit = noTransaction(_.variables.set("version", v))

  override def getModel[E <: Product: ru.TypeTag]: Model.Base[E] = {
    val rm = ru.runtimeMirror(getClass.getClassLoader)
    val companionMirror =
      rm.reflectModule(ru.typeOf[E].typeSymbol.companion.asModule)
    companionMirror.instance match {
      case hm: HasModel[_] ⇒ hm.model.asInstanceOf[Model.Base[E]]
      case _               ⇒ throw InternalError(s"Class ${companionMirror.symbol} is not a model")
    }
  }

  override def getVertexModel[E <: Product: ru.TypeTag]: Model.Vertex[E] = {
    val rm              = ru.runtimeMirror(getClass.getClassLoader)
    val classMirror     = rm.reflectClass(ru.typeOf[E].typeSymbol.asClass)
    val companionSymbol = classMirror.symbol.companion
    val companionMirror = rm.reflectModule(companionSymbol.asModule)
    companionMirror.instance match {
      case hm: HasVertexModel[_] ⇒ hm.model.asInstanceOf[Model.Vertex[E]]
      case _                     ⇒ throw InternalError(s"Class ${companionMirror.symbol} is not a vertex model")
    }
  }

  override def getEdgeModel[E <: Product: ru.TypeTag, FROM <: Product, TO <: Product]: Model.Edge[E, FROM, TO] = {
    val rm = ru.runtimeMirror(getClass.getClassLoader)
    val companionMirror =
      rm.reflectModule(ru.typeOf[E].typeSymbol.companion.asModule)
    companionMirror.instance match {
      case hm: HasEdgeModel[_, _, _] ⇒ hm.model.asInstanceOf[Model.Edge[E, FROM, TO]]
      case _                         ⇒ throw InternalError(s"Class ${companionMirror.symbol} is not an edge model")
    }
  }

  override def createSchemaFrom(schemaObject: Any)(implicit authContext: AuthContext): Unit = {
    def extract[F: ClassTag](o: Any): Seq[F] =
      o.getClass.getMethods.collect {
        case field
            if !Modifier.isPrivate(field.getModifiers) &&
              field.getParameterCount == 0 &&
              classTag[F].runtimeClass.isAssignableFrom(field.getReturnType) ⇒
          field.invoke(o).asInstanceOf[F]
      }.toSeq

    val models     = extract[Model](schemaObject)
    val vertexSrvs = extract[VertexSrv[_, _]](schemaObject)
    val edgeSrvs   = extract[EdgeSrv[_, _, _]](schemaObject) ++ vertexSrvs.flatMap(extract[EdgeSrv[_, _, _]])
    createSchema(models, vertexSrvs, edgeSrvs)
  }

  override def createSchema(models: Seq[Model]): Unit

  override def createSchema(models: Seq[Model], vertexSrvs: Seq[VertexSrv[_, _]], edgeSrvs: Seq[EdgeSrv[_, _, _]])(
      implicit authContext: AuthContext): Unit = {
    createSchema(vertexSrvs.map(_.model) ++ edgeSrvs.map(_.model) ++ models)
    transaction { implicit graph ⇒
      vertexSrvs.foreach(_.createInitialValues())
    }
  }

  override def createVertex[V <: Product](graph: Graph, authContext: AuthContext, model: Model.Vertex[V], v: V): V with Entity = {
    val createdVertex = model.create(v)(this, graph)
    setSingleProperty(createdVertex, "_id", UUID.randomUUID, idMapping)
    setSingleProperty(createdVertex, "_createdAt", new Date, createdAtMapping)
    setSingleProperty(createdVertex, "_createdBy", authContext.userId, createdByMapping)
    logger.trace(s"Created vertex is ${Model.printElement(createdVertex)}")
    model.toDomain(createdVertex)(this)
  }

  override def createEdge[E <: Product, FROM <: Product, TO <: Product](
      graph: Graph,
      authContext: AuthContext,
      model: Model.Edge[E, FROM, TO],
      e: E,
      from: FROM with Entity,
      to: TO with Entity): E with Entity = {
    val edgeMaybe = for {
      f ← graph.V().has(Key("_id") of from._id).headOption()
      t ← graph.V().has(Key("_id") of to._id).headOption()
    } yield {
      val createdEdge = model.create(e, f, t)(this, graph)
      setSingleProperty(createdEdge, "_id", UUID.randomUUID, idMapping)
      setSingleProperty(createdEdge, "_createdAt", new Date, createdAtMapping)
      setSingleProperty(createdEdge, "_createdBy", authContext.userId, createdByMapping)
      logger.trace(s"Create edge ${model.label} from $f to $t: ${Model.printElement(createdEdge)}")
      model.toDomain(createdEdge)(this)
    }
    edgeMaybe.getOrElse(sys.error("vertex not found"))
  }

  override def update(graph: Graph, authContext: AuthContext, model: Model, id: String, fields: Map[FPath, UpdateOps.Type]): Unit = {
    val element: Element = model.get(id)(this, graph)
    setOptionProperty(element, "_updatedAt", Some(new Date), updatedAtMapping)
    setOptionProperty(element, "_updatedBy", Some(authContext.userId), updatedByMapping)
    logger.trace(s"Update $id by ${authContext.userId}")
    fields.foreach {
      case (key, UpdateOps.SetAttribute(value)) ⇒
        val mapping = model.fields(key.toString).asInstanceOf[Mapping[Any, _, _]]
        setProperty(element, key.toString, value, mapping)
        logger.trace(s" - $key = $value")
      case (_, UpdateOps.UnsetAttribute) ⇒ throw InternalError("Unset an attribute is not yet implemented")
      // TODO
    }
  }

  override def getSingleProperty[D, G](element: Element, key: String, mapping: SingleMapping[D, G]): D = {
    val values = element.properties[G](key)
    if (values.hasNext) {
      val v = mapping.toDomain(values.next().value)
      if (values.hasNext)
        throw InternalError(s"Property $key must have only one value but is multivalued on element $element" + Model.printElement(element))
      else v
    } else throw InternalError(s"Property $key is missing on element $element" + Model.printElement(element))
  }

  override def getOptionProperty[D, G](element: Element, key: String, mapping: OptionMapping[D, G]): Option[D] = {
    val values = element
      .properties[G](key)
    if (values.hasNext) {
      val v = Some(mapping.toDomain(values.next().value))
      if (values.hasNext)
        throw InternalError(s"Property $key must have at most one value but is multivalued on element $element" + Model.printElement(element))
      else v
    } else None
  }

  override def getListProperty[D, G](element: Element, key: String, mapping: ListMapping[D, G]): Seq[D] =
    element
      .properties[G](key)
      .asScala
      .map(p ⇒ mapping.toDomain(p.value()))
      .toSeq

  override def getSetProperty[D, G](element: Element, key: String, mapping: SetMapping[D, G]): Set[D] =
    element
      .properties[G](key)
      .asScala
      .map(p ⇒ mapping.toDomain(p.value()))
      .toSet

  override def setSingleProperty[D, G](element: Element, key: String, value: D, mapping: SingleMapping[D, _]): Unit =
    mapping.toGraphOpt(value).foreach(element.property(key, _))

  override def setOptionProperty[D, G](element: Element, key: String, value: Option[D], mapping: OptionMapping[D, _]): Unit =
    value.flatMap(mapping.toGraphOpt).foreach(element.property(key, _))

  override def setListProperty[D, G](element: Element, key: String, values: Seq[D], mapping: ListMapping[D, _]): Unit = {
    element.properties(key).asScala.foreach(_.remove)
    element match {
      case vertex: Vertex ⇒ values.flatMap(mapping.toGraphOpt).foreach(vertex.property(Cardinality.list, key, _))
      case _              ⇒ throw InternalError("Edge doesn't support multi-valued properties")
    }
  }

  override def setSetProperty[D, G](element: Element, key: String, values: Set[D], mapping: SetMapping[D, _]): Unit = {
    element.properties(key).asScala.foreach(_.remove)
    element match {
      case vertex: Vertex ⇒ values.flatMap(mapping.toGraphOpt).foreach(vertex.property(Cardinality.set, key, _))
      case _              ⇒ throw InternalError("Edge doesn't support multi-valued properties")
    }
  }

  val chunkSize: Int = 32 * 1024

  override def loadBinary(initialVertex: Vertex)(implicit graph: Graph): InputStream = loadBinary(initialVertex.value[String]("_id"))

  override def loadBinary(id: String)(implicit graph: Graph): InputStream =
    new InputStream {
      var vertex: GremlinScala[Vertex] = graph.V().has(Key("_id") of id)
      var buffer: Option[Array[Byte]]  = vertex.clone.value[String]("binary").map(Base64.getDecoder.decode).headOption()
      var index                        = 0

      override def read(): Int =
        buffer match {
          case Some(b) if b.length > index ⇒
            val d = b(index)
            index += 1
            d.toInt & 0xff
          case None ⇒ -1
          case _ ⇒
            vertex = vertex.out("nextChunk")
            buffer = vertex.clone.value[String]("binary").map(Base64.getDecoder.decode).headOption()
            index = 0
            read()
        }
    }

  override def saveBinary(is: InputStream)(implicit graph: Graph): Vertex = {

    def readNextChunk: String = {
      val buffer = new Array[Byte](chunkSize)
      val len    = is.read(buffer)
      logger.trace(s"$len bytes read")
      if (len == chunkSize)
        Base64.getEncoder.encodeToString(buffer)
      else if (len > 0)
        Base64.getEncoder.encodeToString(buffer.take(len))
      else
        ""
    }

    val chunks = Iterator
      .continually(readNextChunk)
      .takeWhile { x ⇒
        x.nonEmpty
      }
      .map { data ⇒
        val v = graph.addVertex("binary")
        setSingleProperty(v, "binary", data, UniMapping.stringMapping)
        setSingleProperty(v, "_id", UUID.randomUUID, idMapping)
        v
      }
    if (chunks.isEmpty) {
      logger.debug("Saving empty file")
      val v = graph.addVertex("binary")
      v.property("binary", "")
      v
    } else {
      val firstVertex = chunks.next
      chunks.foldLeft(firstVertex) {
        case (previousVertex, currentVertex) ⇒
          previousVertex.addEdge("nextChunk", currentVertex)
          currentVertex
      }
      firstVertex
    }
  }
}
