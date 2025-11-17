# Ravel Remapper <img src="src/main/resources/META-INF/pluginIcon.svg" width="150" alt="Ravel Logo" align="right">

[![Version](https://img.shields.io/jetbrains/plugin/v/28938-ravel-remapper.svg)](https://plugins.jetbrains.com/plugin/28938-ravel-remapper)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/28938-ravel-remapper.svg)](https://plugins.jetbrains.com/plugin/28938-ravel-remapper)

[![GitHub Release](https://img.shields.io/github/v/release/badasintended/ravel?label=github%20release)](https://github.com/badasintended/ravel/releases)
[![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/badasintended/ravel/total)](https://github.com/badasintended/ravel/releases)

> **ravel** _[rav-uhl]_ verb    
> _raveled, raveling, ravelled, ravelling_   
> to disentangle or unravel the threads or fibers of (a woven or knitted fabric, rope, etc.).

<!-- Plugin description -->

Ravel is a plugin for IntelliJ IDEA to remap source files, based on
[PSI](https://plugins.jetbrains.com/docs/intellij/psi.html) and [Mapping-IO](https://github.com/FabricMC/mapping-io).

Install it from [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/28938-ravel-remapper) 
or download it manually from [GitHub Release](https://github.com/badasintended/ravel/releases).

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
   Select the source and destination namespace as you see fit.

4. Click OK and wait for the remapping to be done

5. Search for `TODO(Ravel)` for remapping errors and fix them manually    
   <img src="https://github.com/badasintended/ravel/blob/master/docs/search-todo.png?raw=true" width="400" alt="Search TODO">

<!-- Plugin description end -->

