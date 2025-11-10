# Ravel Remapper <img src="src/main/resources/META-INF/pluginIcon.svg" width="150" alt="Ravel Logo" align="right">

![Build](https://github.com/badasintended/ravel/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

> **ravel** _[rav-uhl]_ verb    
> _raveled, raveling, ravelled, ravelling_   
> to disentangle or unravel the threads or fibers of (a woven or knitted fabric, rope, etc.).

<!-- Plugin description -->

Ravel is a plugin for IntelliJ IDEA to remap source files, based on
[PSI](https://plugins.jetbrains.com/docs/intellij/psi.html) and [Mapping-IO](https://github.com/FabricMC/mapping-io).

Supports remapping:

- [x] Java
- [x] Kotlin
- [x] [Mixin](https://github.com/FabricMC/Mixin) and [MixinExtras](https://github.com/LlamaLad7/MixinExtras)<sup>1</sup>
- [x] [Class Tweaker / Access Widener](https://github.com/FabricMC/fabric-tooling/tree/main/class-tweaker)

<sup>1</sup>MixinExtras [Expression](https://github.com/LlamaLad7/MixinExtras/wiki/Expressions) is not supported.

## Usage

1. **Commit any changes before attempting to remap your sources!**

2. Right-click the code editor and go to **Refactor** - **Remap Using Ravel**    
   <img src="https://github.com/badasintended/ravel/blob/master/docs/right-click.png?raw=true" width="400" alt="Right Click Action">    
   You can also find it inside the **Refactor** menu at the top menu

3. Select the mappings to use and modules to remap    
   <img src="https://github.com/badasintended/ravel/blob/master/docs/dialog.png?raw=true" width="400" alt="Remapper Dialog">    
   Here, I want to remap Fabric API from Yarn to Mojang Mappings, as there is no direct
   Yarn-to-Mojang mappings, I need to put both Yarn-merged TinyV2 mapping and Mojang ProGuard TXT
   mapping as the input.

    - Download Yarn mappings from https://maven.fabricmc.net/net/fabricmc/yarn/, download the `mergedv2.jar`,
      extract it as a ZIP, and select the `mappings/mappings.tiny` file.
    - Download Mojang mappings from searching the Minecraft version on
      https://piston-meta.mojang.com/mc/game/version_manifest_v2.json, open the JSON url, search for `client_mappings`,
      and download the `client.txt`

   Select the source and destination namespace as you see fit.

4. Click OK and wait for the remapping to be done

5. Search for `TODO(Ravel)` for remapping errors and fix them manually    
   <img src="https://github.com/badasintended/ravel/blob/master/docs/search-todo.png?raw=true" width="400" alt="Search TODO">

<!-- Plugin description end -->

