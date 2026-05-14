package dev.matteomac81888.echo.extensions.exceptions

class ExtensionLoaderException(
    val clazz: String,
    val source: String,
    override val cause: Throwable
) : Exception()