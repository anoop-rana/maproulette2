/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection
import scala.concurrent.duration.FiniteDuration

import anorm.SqlParser.get
import anorm.ToParameterValue
import anorm.{RowParser, ~}
import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model.{Task, TaskReview, ReviewMetrics}
import org.maproulette.framework.psql.{Query, Grouping, GroupField}
import play.api.db.Database

/**
  * @author krotstan
  */
@Singleton
class TaskReviewMetricsRepository @Inject() (override val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = TaskReview.TABLE

  val reviewMetricsParser: RowParser[ReviewMetrics] = {
    get[Int]("total") ~
      get[Int]("requested") ~
      get[Int]("approved") ~
      get[Int]("rejected") ~
      get[Int]("assisted") ~
      get[Int]("disputed") ~
      get[Int]("metaRequested") ~
      get[Int]("metaApproved") ~
      get[Int]("metaRejected") ~
      get[Int]("metaAssisted") ~
      get[Int]("fixed") ~
      get[Int]("falsePositive") ~
      get[Int]("skipped") ~
      get[Int]("alreadyFixed") ~
      get[Int]("tooHard") ~
      get[Double]("totalReviewTime") ~
      get[Int]("tasksWithReviewTime") ~
      get[Long]("user_id").? ~
      get[String]("tag_name").? ~
      get[String]("tag_type").? map {
      case total ~ requested ~ approved ~ rejected ~ assisted ~ disputed ~
            metaRequested ~ metaApproved ~ metaRejected ~ metaAssisted ~
            fixed ~ falsePositive ~ skipped ~ alreadyFixed ~ tooHard ~
            totalReviewTime ~ tasksWithReviewTime ~ userId ~ tagName ~ tagType => {
        new ReviewMetrics(
          total,
          requested,
          approved,
          rejected,
          assisted,
          disputed,
          metaRequested,
          metaApproved,
          metaRejected,
          metaAssisted,
          fixed,
          falsePositive,
          skipped,
          alreadyFixed,
          tooHard,
          if (tasksWithReviewTime > 0) (totalReviewTime / tasksWithReviewTime) else 0,
          userId,
          tagName,
          tagType
        )
      }
    }
  }

  /**
    * Fetches the current task review counts
    *
    * @param query - Query with filters
    * @param useHistory - whether it should pull data from the task review history or
    *                     only use the lastest statuses from the task_review table.
    */
  def getTaskReviewCounts(query: Query, useHistory: Boolean)(
      implicit c: Option[Connection] = None
  ): Map[String, Int] = {
    this.withMRTransaction { implicit c =>
      val reviewCountsParser: RowParser[Map[String, Int]] = {
        get[Int]("total") ~
          get[Int]("approvedCount") ~
          get[Int]("rejectedCount") ~
          get[Int]("assistedCount") ~
          get[Int]("disputedCount") ~
          get[Int]("requestedCount") ~
          get[Int]("metaApprovedCount") ~
          get[Int]("metaRejectedCount") ~
          get[Int]("metaAssistedCount") ~
          get[Int]("metaRequestedCount") ~
          get[Double]("total_review_time") ~
          get[Int]("tasks_with_review_time") ~
          get[Int]("additional_reviews").? map {
          case total ~ approvedCount ~ rejectedCount ~ assistedCount ~ disputedCount ~
                requestedCount ~ metaApprovedCount ~ metaRejectedCount ~ metaAssistedCount ~
                metaRequestedCount ~ totalReviewTime ~ tasksWithReviewTime ~ additionalReviews => {
            val countMap = Map(
              "total"         -> total,
              "approved"      -> approvedCount,
              "rejected"      -> rejectedCount,
              "assisted"      -> assistedCount,
              "disputed"      -> disputedCount,
              "requested"     -> requestedCount,
              "metaApproved"  -> metaApprovedCount,
              "metaRejected"  -> metaRejectedCount,
              "metaAssisted"  -> metaAssistedCount,
              "metaRequested" -> metaRequestedCount,
              "avgReviewTime" -> (if (tasksWithReviewTime > 0)
                                    (totalReviewTime / tasksWithReviewTime).toInt
                                  else 0)
            )

            additionalReviews match {
              case Some(ar) => countMap + ("additionalReviews" -> ar)
              case None     => countMap
            }
          }
        }
      }

      val additionalReviews =
        if (useHistory)
          ", SUM(CASE WHEN (original_reviewer IS NOT NULL) THEN 1 ELSE 0 END) additional_reviews"
        else ""

      query.build(s"""
                       |SELECT count(distinct(task_id)) as total,
                       |COALESCE(sum(case when review_status = ${Task.REVIEW_STATUS_APPROVED} then 1 else 0 end), 0) approvedCount,
                       |COALESCE(sum(case when review_status = ${Task.REVIEW_STATUS_REJECTED} then 1 else 0 end), 0) rejectedCount,
                       |COALESCE(sum(case when review_status = ${Task.REVIEW_STATUS_ASSISTED} then 1 else 0 end), 0) assistedCount,
                       |COALESCE(sum(case when review_status = ${Task.REVIEW_STATUS_DISPUTED} then 1 else 0 end), 0) disputedCount,
                       |COALESCE(sum(case when review_status = ${Task.REVIEW_STATUS_REQUESTED} then 1 else 0 end), 0) requestedCount,
                       |COALESCE(sum(case when meta_review_status = ${Task.REVIEW_STATUS_APPROVED} then 1 else 0 end), 0) metaApprovedCount,
                       |COALESCE(sum(case when meta_review_status = ${Task.REVIEW_STATUS_REJECTED} then 1 else 0 end), 0) metaRejectedCount,
                       |COALESCE(sum(case when meta_review_status = ${Task.REVIEW_STATUS_ASSISTED} then 1 else 0 end), 0) metaAssistedCount,
                       |COALESCE(sum(case when meta_review_status = ${Task.REVIEW_STATUS_REQUESTED} then 1 else 0 end), 0) metaRequestedCount,
                       |COALESCE(SUM(CASE WHEN (review_started_at IS NOT NULL AND
                       |                        reviewed_at IS NOT NULL)
                       |                  THEN (EXTRACT(EPOCH FROM (reviewed_at - review_started_at)) * 1000)
                       |                  ELSE 0 END), 0) total_review_time,
                       |COALESCE(SUM(CASE WHEN (review_started_at IS NOT NULL AND
                       |                        reviewed_at IS NOT NULL)
                       |                  THEN 1 ELSE 0 END), 0) tasks_with_review_time
                       |${additionalReviews}
                       |FROM ${if (useHistory) "task_review_history" else "task_review"} as task_review
        """.stripMargin).as(reviewCountsParser.single)
    }
  }

  def executeReviewMetricsQuery(
      query: Query,
      joinClause: StringBuilder = new StringBuilder(),
      groupByMappers: Boolean = false,
      groupByTags: Boolean = false,
      groupByReviewers: Boolean = false
  ): List[ReviewMetrics] = {
    var groupFields = ""
    var groupBy =
      if (groupByMappers) {
        groupFields = ", review_requested_by as user_id"
        Grouping(GroupField(TaskReview.FIELD_REVIEW_REQUESTED_BY))
      } else if (groupByTags) {
        groupFields = ", TRIM(tags.name) as tag_name, tags.tag_type as tag_type"
        joinClause ++= "INNER JOIN tags_on_tasks tot ON tot.task_id = tasks.id "
        joinClause ++= "INNER JOIN tags tags ON tags.id = tot.tag_id "
        Grouping(GroupField("tag_name", table = Some("")), GroupField("tag_type", table = Some("")))
      } else if (groupByReviewers) {
        groupFields = ", reviewed_by as user_id"
        Grouping(GroupField(TaskReview.FIELD_REVIEWED_BY))
      } else {
        Grouping()
      }

    this.withMRTransaction { implicit c =>
      query
        .build(
          s"""
         SELECT COUNT(*) AS total,
         COUNT(tasks.completed_time_spent) as tasksWithReviewTime,
         COALESCE(SUM(EXTRACT(EPOCH FROM (reviewed_at - review_started_at)) * 1000),0) as totalReviewTime,
         COUNT(review_status) FILTER (where review_status = 0) AS requested,
         COUNT(review_status) FILTER (where review_status = 1) AS approved,
         COUNT(review_status) FILTER (where review_status = 2) AS rejected,
         COUNT(review_status) FILTER (where review_status = 3) AS assisted,
         COUNT(review_status) FILTER (where review_status = 4) AS disputed,
         COUNT(meta_review_status) FILTER (where meta_review_status = 0) AS metaRequested,
         COUNT(meta_review_status) FILTER (where meta_review_status = 1) AS metaApproved,
         COUNT(meta_review_status) FILTER (where meta_review_status = 2) AS metaRejected,
         COUNT(meta_review_status) FILTER (where meta_review_status = 3) AS metaAssisted,
         COUNT(tasks.status) FILTER (where tasks.status = 1) AS fixed,
         COUNT(tasks.status) FILTER (where tasks.status = 2) AS falsePositive,
         COUNT(tasks.status) FILTER (where tasks.status = 3) AS skipped,
         COUNT(tasks.status) FILTER (where tasks.status = 5) AS alreadyFixed,
         COUNT(tasks.status) FILTER (where tasks.status = 6) AS tooHard
         ${groupFields}
         FROM tasks
         INNER JOIN challenges c ON c.id = tasks.parent_id
         LEFT OUTER JOIN task_review ON task_review.task_id = tasks.id
         INNER JOIN projects p ON p.id = c.parent_id
         ${joinClause}
        """,
          groupBy
        )
        .as(reviewMetricsParser.*)
    }
  }
}
