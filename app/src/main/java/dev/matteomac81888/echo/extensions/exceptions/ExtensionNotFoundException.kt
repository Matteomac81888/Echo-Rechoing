package dev.matteomac81888.echo.extensions.exceptions

class ExtensionNotFoundException(val id: String?) : Exception("Extension not found: $id")