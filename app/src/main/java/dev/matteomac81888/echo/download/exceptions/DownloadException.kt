package dev.matteomac81888.echo.download.exceptions

import dev.matteomac81888.echo.download.db.models.DownloadEntity
import dev.matteomac81888.echo.download.db.models.TaskType

data class DownloadException(
    val type: TaskType,
    val downloadEntity: DownloadEntity,
    override val cause: Throwable
) : Exception()