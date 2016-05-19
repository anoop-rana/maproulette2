package org.maproulette.models.dal

import java.sql.Connection
import javax.inject.{Inject, Provider, Singleton}

import anorm._
import anorm.SqlParser._
import org.maproulette.actions.{Actions, ChallengeType}
import org.maproulette.cache.CacheManager
import org.maproulette.exception.UniqueViolationException
import org.maproulette.models.{Challenge, Project, Task}
import org.maproulette.permissions.Permission
import org.maproulette.session.User
import play.api.db.Database
import play.api.libs.json.JsValue

/**
  * The challenge data access layer handles all calls for challenges going to the database. Most
  * worked is delegated to the ParentDAL and BaseDAL, but a couple of specific function like
  * insert and update found here.
  *
  * @author cuthbertm
  */
@Singleton
class ChallengeDAL @Inject() (override val db:Database, taskDAL: TaskDAL,
                              override val tagDAL: TagDAL,
                              projectDAL: Provider[ProjectDAL],
                              override val permission:Permission)
  extends ParentDAL[Long, Challenge, Task] with TagDALMixin[Challenge] {
  // The manager for the challenge cache
  override val cacheManager = new CacheManager[Long, Challenge]
  // The name of the challenge table
  override val tableName: String = "challenges"
  // The name of the table for it's children Tasks
  override val childTable: String = "tasks"
  // The row parser for it's children defined in the TaskDAL
  override val childParser = taskDAL.parser
  override val childColumns: String = taskDAL.retrieveColumns
  override val extraFilters: String = s"challenge_type = ${Actions.ITEM_TYPE_CHALLENGE}"

  /**
    * The row parser for Anorm to enable the object to be read from the retrieved row directly
    * to the Challenge object.
    */
  override val parser: RowParser[Challenge] = {
    get[Long]("challenges.id") ~
      get[String]("challenges.name") ~
      get[Option[String]]("challenges.description") ~
      get[Long]("challenges.parent_id") ~
      get[String]("challenges.instruction") ~
      get[Option[Int]]("challenges.difficulty") ~
      get[Option[String]]("challenges.blurb") ~
      get[Boolean]("challenges.enabled") ~
      get[Int]("challenges.challenge_type") ~
      get[Boolean]("challenges.featured") ~
      get[Option[String]]("challenges.overpass_ql") ~
      get[Option[Int]]("challenges.overpass_status") map {
      case id ~ name ~ description ~ parentId ~ instruction ~ difficulty ~ blurb ~
        enabled ~ challenge_type ~ featured ~ overpassql ~ overpassStatus=>
        new Challenge(id, name, description, parentId, instruction, difficulty, blurb,
          enabled, challenge_type, featured, overpassql, overpassStatus)
    }
  }


  /**
    * This will retrieve the root object in the hierarchy of the object, by default the root
    * object is itself.
    *
    * @param obj Either a id for the challenge, or the challenge itself
    * @param c  The connection if any
    * @return The object that it is retrieving
    */
  override def retrieveRootObject(obj:Either[Long, Challenge], user:User)(implicit c: Connection): Option[Project] = {
    obj match {
      case Left(id) =>
        permission.hasReadAccess(ChallengeType(), user)(id)
        projectDAL.get().cacheManager.withOptionCaching { () =>
          withMRConnection { implicit c =>
            SQL"""SELECT p.* FROM projects p
             INNER JOIN challenges c ON c.parent_id = p.id
             WHERE c.id = $id
           """.as(projectDAL.get().parser.*).headOption
          }
        }
      case Right(challenge) =>
        permission.hasReadAccess(challenge, user)
        projectDAL.get().cacheManager.withOptionCaching { () =>
          withMRConnection { implicit c =>
            SQL"""SELECT * FROM projects WHERE id = ${challenge.parent}""".as(projectDAL.get().parser.*).headOption
          }
        }
    }
  }

  /**
    * Inserts a new Challenge object into the database. It will also place it in the cache after
    * inserting the object.
    *
    * @param challenge The challenge to insert into the database
    * @return The object that was inserted into the database. This will include the newly created id
    */
  override def insert(challenge: Challenge, user:User)(implicit c:Connection=null): Challenge = {
    permission.hasWriteAccess(challenge, user)
    cacheManager.withOptionCaching { () =>
      withMRTransaction { implicit c =>
        SQL"""INSERT INTO challenges (name, parent_id, difficulty, description, blurb,
                                      instruction, enabled, challenge_type, featured, overpass_ql, overpass_status)
              VALUES (${challenge.name}, ${challenge.parent}, ${challenge.difficulty},
                      ${challenge.description}, ${challenge.blurb}, ${challenge.instruction},
                      ${challenge.enabled}, ${challenge.challengeType}, ${challenge.featured},
                      ${challenge.overpassQL}, ${challenge.overpassStatus}
                      ) ON CONFLICT(parent_id, LOWER(name)) DO NOTHING RETURNING *""".as(parser.*).headOption
      }
    } match {
      case Some(value) => value
      case None => throw new UniqueViolationException(s"Challenge with name ${challenge.name} already exists in the database")
    }
  }

  /**
    * Updates a challenge. Uses the updatingCache so will first retrieve the object and make sure
    * to update only values supplied by the json. After updated will update the cache as well
    *
    * @param updates The updates in json format
    * @param id The id of the object that you are updating
    * @return An optional object, it will return None if no object found with a matching id that was supplied
    */
  override def update(updates:JsValue, user:User)(implicit id:Long, c:Connection=null): Option[Challenge] = {
    cacheManager.withUpdatingCache(Long => retrieveById) { implicit cachedItem =>
      permission.hasWriteAccess(cachedItem, user)
      withMRTransaction { implicit c =>
        val name = (updates \ "name").asOpt[String].getOrElse(cachedItem.name)
        val parentId = (updates \ "parentId").asOpt[Long].getOrElse(cachedItem.parent)
        val difficulty = (updates \ "difficulty").asOpt[Int].getOrElse(cachedItem.difficulty.getOrElse(Challenge.DIFFICULTY_EASY))
        val description =(updates \ "description").asOpt[String].getOrElse(cachedItem.description.getOrElse(""))
        val blurb = (updates \ "blurb").asOpt[String].getOrElse(cachedItem.blurb.getOrElse(""))
        val instruction = (updates \ "instruction").asOpt[String].getOrElse(cachedItem.instruction)
        val enabled = (updates \ "enabled").asOpt[Boolean].getOrElse(cachedItem.enabled)
        val featured = (updates \ "featured").asOpt[Boolean].getOrElse(cachedItem.featured)
        val overpassQL = (updates \ "overpassQL").asOpt[String].getOrElse(cachedItem.overpassQL.getOrElse(""))
        val overpassStatus = (updates \ "overpassStatus").asOpt[Int].getOrElse(cachedItem.overpassStatus.getOrElse(Challenge.OVERPASS_STATUS_NA))

        SQL"""UPDATE challenges SET name = $name,
                                    parent_id = $parentId,
                                    difficulty = $difficulty,
                                    description = $description,
                                    blurb = $blurb,
                                    instruction = $instruction,
                                    enabled = $enabled,
                                    featured = $featured,
                                    overpass_ql = $overpassQL,
                                    overpass_status = $overpassStatus
              WHERE id = $id RETURNING *""".as(parser.*).headOption
      }
    }
  }

  /**
    * Gets the featured challenges
    *
    * @param limit The number of challenges to retrieve
    * @param offset For paging, ie. the page number starting at 0
    * @param enabledOnly if true will only return enabled challenges
    * @return list of challenges
    */
  def getFeaturedChallenges(limit:Int, offset:Int, enabledOnly:Boolean=true)(implicit c:Connection=null) : List[Challenge] = {
    withMRConnection { implicit c =>
      val query = s"""SELECT * FROM challenges c
                      WHERE featured = TRUE ${enabled(enabledOnly)}
                      AND 0 < (SELECT COUNT(*) FROM tasks WHERE parent_id = c.id)
                      LIMIT ${sqlLimit(limit)} OFFSET $offset"""
      SQL(query).as(parser.*)
    }
  }

  /**
    * Get the Hot challenges, these are challenges that have the most activity
    *
    * @param limit the number of challenges to retrieve
    * @param offset For paging, ie. the page number starting at 0
    * @param enabledOnly if true will only return enabled challenges
    * @return List of challenges
    */
  def getHotChallenges(limit:Int, offset:Int, enabledOnly:Boolean=true)(implicit c:Connection=null) : List[Challenge] = {
    List.empty
  }

  /**
    * Gets the new challenges
    *
    * @param limit The number of challenges to retrieve
    * @param offset For paging ie. the page number starting at 0
    * @param enabledOnly if true will only return enabled challenges
    * @return list of challenges
    */
  def getNewChallenges(limit:Int, offset:Int, enabledOnly:Boolean=true)(implicit c:Connection=null) : List[Challenge] = {
    withMRConnection { implicit c =>
      val query = s"""SELECT * FROM challenges
                      WHERE ${enabled(enabledOnly, "", "")}
                      ${order(Some("created"), "DESC")}
                      LIMIT ${sqlLimit(limit)} OFFSET $offset"""
      SQL(query).as(parser.*)
    }
  }

  /**
    * The summary for a challenge is the status with the number of tasks associated with each status
    * underneath the given challenge
    *
    * @param id The id for the challenge
    * @return Map of status codes mapped to task counts
    */
  def getSummary(id:Long)(implicit c:Connection=null) : Map[Int, Int] = {
    withMRConnection { implicit c =>
      val summaryParser = int("count") ~ get[Option[Int]]("tasks.status") map {
        case count ~ status => status.getOrElse(0) -> count
      }
      SQL"""SELECT COUNT(*) as count, status FROM tasks WHERE parent_id = $id GROUP BY status"""
        .as(summaryParser.*).toMap
    }
  }
}