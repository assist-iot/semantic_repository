package eu.assistiot.semantic_repo.core.datamodel

import eu.assistiot.semantic_repo.core.AppConfig
import eu.assistiot.semantic_repo.core.Exceptions.*
import eu.assistiot.semantic_repo.core.datamodel.search.RootSearchProvider
import org.mongodb.scala.*
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters as f
import org.mongodb.scala.model.Sorts as s

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/**
 * Generic set of MongoDB documents. Shows only some view of the entire collection.
 *
 * @param items documents in the current view (page)
 * @param totalCount count of all documents in the collection, matching the given filter
 * @param inViewCount count of the documents in the current view (page)
 * @param page number of the current page (1-based)
 * @param pageSize maximum number of documents to display on the page
 * @tparam T Case class modelling the document type. For example: MongoModel.Namespace
 */
case class MongoSet[T](items: Iterable[T], totalCount: Int, inViewCount: Int, page: Int, pageSize: Int)

extension [T : ClassTag : RootSearchProvider] (collection: MongoCollection[T]) {
  /**
   * Similar to MongoCollection.find(), but returns a MongoSet instead.
   * @param params paging, sorting, filtering parameters (validated)
   * @param filter Filter constructed as with MongoCollection.find()
   * @param projection optional: projection constructed as with MongoCollection.projection()
   * @return SingleObservable of a MongoSet
   */
  def findToSet(params: MongoSetParamsValidated[T], filter: Bson, projection: Option[Bson] = None):
  SingleObservable[MongoSet[T]] =
    def project(op: FindObservable[T]) =
      projection match
        case Some(proj) => op.projection(proj)
        case _ => op

    // Merge the user-specified filters with those that were generated by other logic (e.g., for path handling)
    val fullFilter = f.and(Seq(filter) ++ params.filters.map((k, v) => f.eq(k, v))*)

    val sorting = params.sortParams match
      case Some((order, sortBy)) => order match
        case "descending" => s.descending(sortBy)
        case _ => s.ascending(sortBy)
      case _ => f.empty()

    val op = project(collection.find[T](fullFilter))
      .sort(sorting)
      .skip((params.page - 1) * params.pageSize)
      .limit(params.pageSize)
      .collect()
      .zip(collection.countDocuments(fullFilter))

    op.map( { (items: Seq[T], totalCount: Long) =>
      MongoSet(items, totalCount.toInt, items.length, params.page, params.pageSize)
    } ).toSingle

  /**
   * Similar to MongoCollection.find(), but returns a MongoSet instead.
   * @param params see the other overload
   * @return SingleObservable of a MongoSet if successful
   */
  def findToSet(params: MongoSetParamsValidated[T]): SingleObservable[MongoSet[T]] =
    collection.findToSet(params, f.empty())
}
