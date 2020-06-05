#!/usr/bin/env bash

set -e

sbt ci-release
SCALAJS_VERSION=0.6.33 sbt ci-release
SCALAJS_VERSION=1.0.1 sbt ci-release
