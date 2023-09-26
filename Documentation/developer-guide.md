# Developer Guide

[TOC]

## Building

Gitiles requires [Bazel](https://bazel.build/) to build.

You need to use Java for building Gitiles. You can install Bazel from
bazel.build: https://bazel.build/versions/master/docs/install.html .
Alternatively, you can use `apt-get`.

```
$ sudo apt-get update
$ sudo apt-get install bazel
```

The best way to build and run gitiles is to use bazelisk.

```
$ go install github.com/bazelbuild/bazelisk@latest
$ export PATH=$PATH:$(go env GOPATH)/bin
```

Make sure to initialize and update the git submodules:
```
git submodule update --init
```

You are now ready to build and test.

```
$ bazelisk build //:gitiles
$ bazelisk test //...
```

## Troubleshooting

If you encounter build errors such as:

```
Error in execute: Argument 0 of execute is neither a path, label, nor string.
```

Make sure you have `python` available in your PATH. Since Debian 11 and Ubuntu
20.04 LTS (focal), there is no `/usr/bin/python` provided by default. You can
install a package to provide a link to Python 3:

```
sudo apt-get install python-is-python3
```

Upon uploading your new CL, if you encounter a message related to a
missing `Change-Id`, you are missing the commit hook. Likely the command
to download it should appear in the Hint section of the message. If it
does not, use the following command:

```
f=`git rev-parse --git-dir`/hooks/commit-msg ; mkdir -p $(dirname $f) ; curl -Lo $f https://gerrit-review.googlesource.com/tools/hooks/commit-msg ; chmod +x $f
```

## Running Locally and Testing

```
cd /path/to/repositories  # Don't run from the gitiles repo.
/path/to/gitiles/tools/run_dev.sh
```

This will recompile and start a development server.  Open
http://localhost:8080/ to view your local copy of gitiles, which
will serve any repositories under `/path/to/repositories`.

Passing `--debug` option to `tools/run_dev.sh` will suspend the runtime until a remote debugger connects to port 5005. If you don't want to suspend the runtime, make sure to assign value `n` to environment variable `DEFAULT_JVM_DEBUG_SUSPEND`:

```
cd /path/to/repositories  # Don't run from the gitiles repo.
export DEFAULT_JVM_DEBUG_SUSPEND=n; /path/to/gitiles/tools/run_dev.sh --debug
```

To run unit tests, refer to the aforementioned bazel test command.

## Pushing your changes
This repository does not work with `repo` tool. To push your CL to
staging, use the following command.

```
git push origin HEAD:refs/for/master
```


## Eclipse IDE

If you'd like to use Eclipse to edit Gitiles, first generate a project file:

```
tools/eclipse/project.sh
```

Import the project in Eclipse:

```
File -> Import -> Existing Projects into Workpace
```

The project only needs to be rebuilt if the source roots or third-party
libraries have changed. For best results, ensure the project is closed in
Eclipse before rebuilding.

## Running/Debugging from Eclipse IDE

Running Gitiles from Eclipse requires setting the
`com.google.gitiles.sourcePath` system property. The property value has to be
the root folder of the Gitiles source code, for example:

````
-Dcom.google.gitiles.sourcePath=/home/johndoe/git/gitiles
````

## Code Style

Java code in Gitiles follows the [Google Java Style Guide][java-style]
with a 100-column limit.

Code should be automatically formatted using [google-java-format][fmt]
prior to sending a code review.  There is currently no Eclipse
formatter, but the tool can be run from the command line:

```
java -jar /path/to/google-java-format.jar -i path/to/java/File.java
```

CSS in Gitiles follows the [SUIT CSS naming conventions][suit].

[java-style]: https://google.github.io/styleguide/javaguide.html
[fmt]: https://github.com/google/google-java-format
[suit]: https://github.com/suitcss/suit/blob/master/doc/naming-conventions.md

## Code Review

Gitiles uses Gerrit for code review:
https://gerrit-review.googlesource.com/

Gitiles uses the ["git push" workflow][1] with server
https://gerrit.googlesource.com/gitiles.  You will need a
[generated cookie][2].

[1]: https://gerrit-review.googlesource.com/Documentation/user-upload.html#_git_push
[2]: https://gerrit.googlesource.com/new-password

Gerrit depends on "Change-Id" annotations in your commit message.
If you try to push a commit without one, it will explain how to
install the proper git-hook:

```
curl -Lo `git rev-parse --git-dir`/hooks/commit-msg \
    https://gerrit-review.googlesource.com/tools/hooks/commit-msg
chmod +x `git rev-parse --git-dir`/hooks/commit-msg
```

Before you create your local commit (which you'll push to Gerrit)
you will need to set your email to match your Gerrit account:

```
git config --local --add user.email foo@bar.com
```

Normally you will create code reviews by pushing for master:

```
git push origin HEAD:refs/for/master
```

## Releases

Gitiles artifacts are published to the [gerrit-maven
bucket](http://gerrit-maven.storage.googleapis.com/). To release a new version,
you must have write access to this bucket. See
[Deploy Gerrit
Artifacts](https://gerrit-review.googlesource.com/Documentation/dev-release-deploy-config.html)
for PGP key setup and Google Cloud Storage access setup.

First, increment `GITILES_VERSION` in `version.bzl`, Gitiles uses
[Semantic Versioning](https://semver.org).
Get your change reviewed and submitted.

Then, run:

```
./tools/maven/mvn.sh deploy
```

Tag the release with a signed, annotated tag matching the version number, for
example "v1.1.0".

Once released, Maven projects can consume the new version as long as they point
at the proper repository URL. Similarly, Bazel projects using the `maven_jar`
bazlet can use the new version with `repository = GERRIT`.
