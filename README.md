The app version (a.k.a Version Code) is based on the following scheme:

* Major version number
* Minor version number
* Release status number (snapshot/release candidate/public release)

The rules are as follow:

* The major version uses two digits
* The minor version uses two digits
* The release status uses two digits
* The major version should be bumped when introducing new features
* The minor version should be bumped whenever there is a (simple/hot) fix
* The release status must be 0 while developing (snapshot)
* The release status must be in [1;98] while testing releases (release candidate)
* The release status must be 99 for a public release

The readable app version (a.k.a Version Name) is based on the app version with the following schemes:

* Snapshots are named after <major version>.<minor version>-SNAPSHOT
* Release candidates are named after <major version>.<minor version>-RC<release status>
* Public releases are named after <major version>.<minor version>

TODO: implement an automatic translation from app version to readable app version
TODO: implement an automatic version bumping task when pushing/merging/integrating features/fixes
