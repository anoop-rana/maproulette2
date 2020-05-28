/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.FiniteDuration
import play.api.libs.json.JsValue

import org.maproulette.exception.NotFoundException
import org.maproulette.framework.model._
import org.maproulette.framework.psql.{Query, _}
import org.maproulette.framework.psql.filter.{BaseParameter, _}
import org.maproulette.framework.repository.TaskRepository
import org.maproulette.models.Task
import org.maproulette.models.dal.TaskDAL
import org.maproulette.cache.CacheManager

/**
  * Service layer for TaskReview
  *
  * @author krotstan
  */
@Singleton
class TaskService @Inject() (repository: TaskRepository, taskDAL: TaskDAL) {

  /**
    * Retrieves an object of that type
    *
    * @param id The identifier for the object
    * @return An optional object, None if not found
    */
  def retrieve(id: Long): Option[Task] = this.taskDAL.retrieveById(id)

  /**
    * Updates task completiong responses
    *
    * @param duration - age of task reviews to treat as 'expired'
    * @return The number of taskReviews that were expired
    */
  def updateCompletionResponses(taskId: Long, user: User, completionResponses: JsValue): Unit = {
    val task: Task = this.retrieve(taskId) match {
      case Some(t) => t
      case None =>
        throw new NotFoundException(
          s"Task with $taskId not found, cannot update completion responses."
        )
    }

    this.repository.updateCompletionResponses(task, user, completionResponses)
    this.taskDAL.cacheManager.withOptionCaching { () =>
      Some(task.copy(completionResponses = Some(completionResponses.toString())))
    }
  }
}