rootProject.name = "msika"

// The modules live under platform/, but are addressed by their short names.
val modules = listOf("msika-ledger", "msika-core", "msika-api", "zamsim", "elevysim")
modules.forEach { name ->
    include(name)
    project(":$name").projectDir = file("platform/$name")
}
