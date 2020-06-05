# Copyright (C) 2016 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Merge maven files

load("@rules_java//java:defs.bzl", "java_import")

def cmd(jars):
    return ("$(location //tools:merge_jars) $@ " +
            " ".join(["$(location %s)" % j for j in jars]))

def merge_maven_jars(name, srcs, **kwargs):
    native.genrule(
        name = "%s__merged_bin" % name,
        cmd = cmd(srcs),
        tools = srcs + ["//tools:merge_jars"],
        outs = ["%s__merged.jar" % name],
    )
    java_import(
        name = name,
        jars = [":%s__merged_bin" % name],
        **kwargs
    )