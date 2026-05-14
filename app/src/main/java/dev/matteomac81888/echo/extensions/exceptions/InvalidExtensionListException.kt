package dev.matteomac81888.echo.extensions.exceptions

class InvalidExtensionListException(
    val link: String, override val cause: Throwable
) : Exception()