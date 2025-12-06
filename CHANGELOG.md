<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Ravel Changelog

## [Unreleased]

## [0.4.0]

- UI: fixed progress modal not showing
- Java: added support for remapping project classes
- Mixin: remap mixin class name depending on new target class name
- Mixin: also remap mixin configuration JSON
- FMJ: remap entrypoints inside fabric.mod.json

## [0.3.3]

- Mixin: remap accessor method directly instead of adding value parameter

## [0.3.2]

- Java: fix parameter types not getting remapped
- Mixin: remove `remap=false` check, try to remap the target anyway
- Kotlin: more robust Java property access remap
- UI: remapping progress modal now reports how many changes to do

## [0.3.1]

- Java: fix parameter types not getting remapped
- Mixin: remove `remap=false` check, try to remap the target anyway
- Kotlin: more robust Java property access remap

## [0.3.0]

- Added mappings downloader, currently supports downloading Yarn and Mojang Mappings

## [0.2.1]

### Fixed

- A module can no longer be selected multiple times

## [0.2.0]

- Initial support for remapping Kotlin sources

## [0.1.1]

### Fixed

- Removed usage of internal fleet.util.Multimap

## [0.1.0]

### Added

- Initial release

[Unreleased]: https://github.com/badasintended/ravel/compare/0.4.0...HEAD
[0.4.0]: https://github.com/badasintended/ravel/compare/0.3.3...0.4.0
[0.3.3]: https://github.com/badasintended/ravel/compare/0.3.2...0.3.3
[0.3.2]: https://github.com/badasintended/ravel/compare/0.3.1...0.3.2
[0.3.1]: https://github.com/badasintended/ravel/compare/0.3.0...0.3.1
[0.3.0]: https://github.com/badasintended/ravel/compare/0.2.1...0.3.0
[0.2.1]: https://github.com/badasintended/ravel/compare/0.2.0...0.2.1
[0.2.0]: https://github.com/badasintended/ravel/compare/0.1.1...0.2.0
[0.1.1]: https://github.com/badasintended/ravel/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/badasintended/ravel/commits/0.1.0
