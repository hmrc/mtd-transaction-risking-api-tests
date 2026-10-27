package uk.gov.hmrc.api.specs.support

import com.mongodb.ConnectionString
import com.mongodb.client.MongoClients
import com.mongodb.client.model.Filters
import com.typesafe.config.ConfigFactory
import org.bson.Document
import org.bson.types.ObjectId

import java.util
import scala.jdk.CollectionConverters.*

final case class RetryWorkItem (
  id: ObjectId,
  status: String,
  failureCount: Int                             
)

final class RetryMongo extends AutoCloseable {
  private val connection = new ConnectionString(
    ConfigFactory.load().getString("local.retry-tests.mongodb-uri")
  )
  
  private val client = MongoClients.create(connection)
  private val database = client.getDatabase(connection.getDatabase)
  
  def ping(): Unit = {
    database.runCommand(new Document("ping", 1))
    ()
  }
  
  def find(
    collection: String,
    correlationId: String      
  ): Seq[RetryWorkItem] =
    database
      .getCollection(collection)
      .find(Filters.eq("item.correlationId", correlationId))
      .into(new util.ArrayList[Document]())
      .asScala
      .toSeq
      .map(document =>
        RetryWorkItem(
          document.getObjectId("_id"),
          document.getString("status"),
          document.get("failureCount", classOf[Number]).intValue()
        )
      )
      
      override def close(): Unit = client.close()

}