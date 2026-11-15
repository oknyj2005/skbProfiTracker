plugins {
    id("dev.deftu.gradle.multiversion-root") version "2.73.0" // Applies preprocessing for multiple versions of Minecraft and/or multiple mod loaders.
}

preprocess {
    strictExtraMappings.set(true)

    createNode("26.2-fabric", 26_02_00, "mojang")
}
