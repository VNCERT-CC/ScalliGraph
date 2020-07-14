package org.thp.scalligraph.query

import java.util.{Collection => JCollection}

import gremlin.scala.P
import org.apache.tinkerpop.gremlin.process.traversal.TextP
import org.scalactic.Accumulation._
import org.scalactic.Good
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.controllers._
import org.thp.scalligraph.models.{Database, Mapping}
import org.thp.scalligraph.steps.StepsOps._
import org.thp.scalligraph.steps.{Converter, IdMapping, Traversal, UntypedTraversal}
import play.api.Logger

import scala.collection.JavaConverters._
import scala.reflect.runtime.{universe => ru}

trait InputFilter[PD, PG, PC <: Converter[PD, PG]] extends InputQuery[PD, PG, PC] {
  def apply[D, G, C <: Converter[D, G]](
      db: Database,
      publicProperties: List[PublicProperty[_, _, _]],
      traversalType: ru.Type,
      traversal: Traversal[D, G, C],
      authContext: AuthContext
  ): Traversal[D, G, C]
}

case class PredicateFilter[PD, PG, PC <: Converter[PD, PG]](fieldName: String, predicate: P[PG]) extends InputFilter[PD, PG, PC] {
  override def apply[D, G, C <: Converter[D, G]](
      db: Database,
      publicProperties: List[PublicProperty[_, _, _]],
      traversalType: ru.Type,
      traversal: Traversal[D, G, C],
      authContext: AuthContext
  ): Traversal[D, G, C] = {
    val property = getProperty(publicProperties, traversalType, fieldName, authContext)
    if (property.mapping == IdMapping) {
      val newValue = predicate.getValue match {
        case c: JCollection[_] => c.asScala.map(db.toId).asJavaCollection
        case other             => db.toId(other)
      }
      predicate.asInstanceOf[P[Any]].setValue(newValue)
    }
    traversal.filter { t =>
      property.get(t, FPath(fieldName)).is(db.mapPredicate(predicate))
    }
  }
}

case class IsDefinedFilter[PD, PG, PC <: Converter[PD, PG]](fieldName: String) extends InputFilter[PD, PG, PC] {
  override def apply[D, G, C <: Converter[D, G]](
      db: Database,
      publicProperties: List[PublicProperty[_, _, _]],
      traversalType: ru.Type,
      traversal: Traversal[D, G, C],
      authContext: AuthContext
  ): Traversal[D, G, C] = {
    val property = getProperty(publicProperties, traversalType, fieldName, authContext)
    traversal.filter(t => property.get(t, FPath(fieldName)))
  }
}

case class OrFilter[PD, PG, PC <: Converter[PD, PG]](inputFilters: Seq[InputFilter[_, _, _]]) extends InputFilter[PD, PG, PC] {

  override def apply(
      db: Database,
      publicProperties: List[PublicProperty[_, _, _]],
      traversalType: ru.Type,
      step: UntypedTraversal,
      authContext: AuthContext
  ): UntypedTraversal =
    inputFilters.map(ff => (s: UntypedTraversal) => ff(db, publicProperties, traversalType, s, authContext)) match {
      case Seq(f) => step.filter(f)
      case Seq()  => step.limit(0)
      case f      => step.filter(_.or(f: _*))
    }
}

case class AndFilter(inputFilters: Seq[InputFilter]) extends InputFilter {

  override def apply(
      db: Database,
      publicProperties: List[PublicProperty[_, _, _]],
      traversalType: ru.Type,
      step: UntypedTraversal,
      authContext: AuthContext
  ): UntypedTraversal =
    inputFilters.map(ff => (s: UntypedTraversal) => ff(db, publicProperties, traversalType, s, authContext)) match {
      case Seq(f)      => step.filter(f)
      case Seq()       => step.filter(_.not(identity))
      case Seq(f @ _*) => step.filter(_.and(f: _*))
    }
}

case class NotFilter(inputFilter: InputFilter) extends InputFilter {

  override def apply(
      db: Database,
      publicProperties: List[PublicProperty[_, _, _]],
      traversalType: ru.Type,
      step: UntypedTraversal,
      authContext: AuthContext
  ): UntypedTraversal = {
    val criteria: UntypedTraversal => UntypedTraversal = (s: UntypedTraversal) => inputFilter(db, publicProperties, traversalType, s, authContext)
    step.filter(_.not(criteria))
  }
}

object YesFilter extends InputFilter {

  override def apply(
      db: Database,
      publicProperties: List[PublicProperty[_, _, _]],
      traversalType: ru.Type,
      step: UntypedTraversal,
      authContext: AuthContext
  ): UntypedTraversal = step
}

class IdFilter(id: String) extends InputFilter {

  override def apply(
      db: Database,
      publicProperties: List[PublicProperty[_, _, _]],
      traversalType: ru.Type,
      step: UntypedTraversal,
      authContext: AuthContext
  ): UntypedTraversal = step.getByIds(id)
}

object InputFilter {
  lazy val logger: Logger = Logger(getClass)

  def is(field: String, value: Any): PredicateFilter = PredicateFilter(field, P.is(value))

  def neq(field: String, value: Any): PredicateFilter = PredicateFilter(field, P.neq(value))

  def lt(field: String, value: Any): PredicateFilter = PredicateFilter(field, P.lt(value))

  def gt(field: String, value: Any): PredicateFilter = PredicateFilter(field, P.gt(value))

  def lte(field: String, value: Any): PredicateFilter = PredicateFilter(field, P.lte(value))

  def gte(field: String, value: Any): PredicateFilter = PredicateFilter(field, P.gte(value))

  def isDefined(field: String): IsDefinedFilter = IsDefinedFilter(field)

  def between(field: String, from: Any, to: Any): PredicateFilter = PredicateFilter(field, P.between(from, to))

  def inside(field: String, from: Any, to: Any): PredicateFilter = PredicateFilter(field, P.inside(from, to))

  def in(field: String, values: Any*): PredicateFilter = PredicateFilter(field, P.within(values))

  def startsWith(field: String, value: String): PredicateFilter = PredicateFilter(field, TextP.startingWith(value))

  def endsWith(field: String, value: String): PredicateFilter = PredicateFilter(field, TextP.endingWith(value))

  def or(filters: Seq[InputFilter]): OrFilter = OrFilter(filters)

  def and(filters: Seq[InputFilter]): AndFilter = AndFilter(filters)

  def not(filter: InputFilter): NotFilter = NotFilter(filter)

  def yes: YesFilter.type = YesFilter

  def withId(id: String): InputFilter = new IdFilter(id)

  def like(field: String, value: String): PredicateFilter = {
    val s = value.headOption.contains('*')
    val e = value.lastOption.contains('*')
    if (s && e) PredicateFilter(field, TextP.containing(value.tail.dropRight(1)))
    else if (s) PredicateFilter(field, TextP.endingWith(value.tail))
    else if (e) PredicateFilter(field, TextP.startingWith(value.dropRight(1)))
    else PredicateFilter(field, P.eq(value))
  }

  def fieldsParser(
      tpe: ru.Type,
      properties: Seq[PublicProperty[_, _, _]],
      globalParser: ru.Type => FieldsParser[InputFilter]
  ): FieldsParser[InputFilter] = {
    def propParser(name: String): FieldsParser[Any] = {
      val prop = PublicProperty.getProperty(properties, tpe, name)
      prop.fieldsParser.map(prop.fieldsParser.formatName)(v => prop.mapping.asInstanceOf[Mapping[_, Any, Any]].toGraph(v))
    }

    FieldsParser("query") {
      case (path, FObjOne("_and", FSeq(fields))) =>
        fields.zipWithIndex.validatedBy { case (field, index) => globalParser(tpe)((path :/ "_and").toSeq(index), field) }.map(and)
      case (path, FObjOne("_or", FSeq(fields))) =>
        fields.zipWithIndex.validatedBy { case (field, index) => globalParser(tpe)((path :/ "_or").toSeq(index), field) }.map(or)
      case (path, FObjOne("_not", field))                                      => globalParser(tpe)(path :/ "_not", field).map(not)
      case (_, FObjOne("_any", _))                                             => Good(yes)
      case (_, FObjOne("_lt", FFieldValue(key, field)))                        => propParser(key)(field).map(value => lt(key, value))
      case (_, FObjOne("_lt", FDeprecatedObjOne(key, field)))                  => propParser(key)(field).map(value => lt(key, value))
      case (_, FObjOne("_gt", FFieldValue(key, field)))                        => propParser(key)(field).map(value => gt(key, value))
      case (_, FObjOne("_gt", FDeprecatedObjOne(key, field)))                  => propParser(key)(field).map(value => gt(key, value))
      case (_, FObjOne("_lte", FFieldValue(key, field)))                       => propParser(key)(field).map(value => lte(key, value))
      case (_, FObjOne("_lte", FDeprecatedObjOne(key, field)))                 => propParser(key)(field).map(value => lte(key, value))
      case (_, FObjOne("_gte", FFieldValue(key, field)))                       => propParser(key)(field).map(value => gte(key, value))
      case (_, FObjOne("_gte", FDeprecatedObjOne(key, field)))                 => propParser(key)(field).map(value => gte(key, value))
      case (_, FObjOne("_ne", FFieldValue(key, field)))                        => propParser(key)(field).map(value => neq(key, value))
      case (_, FObjOne("_ne", FDeprecatedObjOne(key, field)))                  => propParser(key)(field).map(value => neq(key, value))
      case (_, FObjOne("_is", FFieldValue(key, field)))                        => propParser(key)(field).map(value => is(key, value))
      case (_, FObjOne("_is", FDeprecatedObjOne(key, field)))                  => propParser(key)(field).map(value => is(key, value))
      case (_, FObjOne("_startsWith", FFieldValue(key, FString(value))))       => Good(startsWith(key, value))
      case (_, FObjOne("_startsWith", FDeprecatedObjOne(key, FString(value)))) => Good(startsWith(key, value))
      case (_, FObjOne("_endsWith", FFieldValue(key, FString(value))))         => Good(endsWith(key, value))
      case (_, FObjOne("_endsWith", FDeprecatedObjOne(key, FString(value))))   => Good(endsWith(key, value))
      case (_, FObjOne("_id", FString(id)))                                    => Good(withId(id))
      case (_, FObjOne("_between", FFieldFromTo(key, fromField, toField))) =>
        withGood(propParser(key)(fromField), propParser(key)(toField))(between(key, _, _))
      case (_, FObjOne("_string", _)) =>
        logger.warn("string filter is not supported, it is ignored")
        Good(yes)
      case (_, FObjOne("_in", o: FObject)) =>
        for {
          key <- FieldsParser.string(o.get("_field"))
          s   <- FSeq.parser(o.get("_values"))
          valueParser = propParser(key)
          values <- s.values.validatedBy(valueParser.apply)
        } yield in(key, values: _*)
      case (_, FObjOne("_contains", FString(path)))                            => Good(isDefined(path))
      case (_, FObjOne("_like", FFieldValue(key, FString(value))))             => Good(like(key, value))
      case (_, FObjOne("_like", FDeprecatedObjOne(key, FString(value))))       => Good(like(key, value))
      case (_, FObjOne("_wildcard", FFieldValue(key, FString(value))))         => Good(like(key, value))
      case (_, FObjOne("_wildcard", FDeprecatedObjOne(key, FString(value))))   => Good(like(key, value))
      case (_, FFieldValue(key, field))                                        => propParser(key)(field).map(value => is(key, value))
      case (_, FDeprecatedObjOne(key, field)) if !key.headOption.contains('_') => propParser(key)(field).map(value => is(key, value))
      case (_, FObject(kv)) if kv.isEmpty                                      => Good(yes)
    }
  }
}
