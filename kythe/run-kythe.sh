#!/bin/sh
set -eu
KYTHE_DIR=/opt/kythe/kythe-v0.0.20


# DO NOT SUBMIT.  Kythe needs a patch to deal with empty java_libraries.

bazel build --experimental_action_listener=//kythe:extract_kindex //...

tmp=/tmp/kythe
mkdir -p ${tmp}

srcdir=$(pwd)
for f in $(find bazel-out/ -type f -name '*.java.kindex') ;  do
  out=$(echo $f | sha1sum |tr -d ' ' | tr -d '-')

  # TODO(hanwen): use the server instead.
  (cd ${tmp} &&
   java -jar ${KYTHE_DIR}/indexers/java_indexer.jar ${srcdir}/${f} > ${out}.entries)

  ${KYTHE_DIR}/tools/write_entries --graphstore leveldb:${tmp}/gs < ${tmp}/${out}.entries

done

${KYTHE_DIR}/tools/write_tables --graphstore leveldb:${tmp}/gs --out ${tmp}/serving

${KYTHE_DIR}/tools/http_server \
  --public_resources ${KYTHE_DIR}/web/ui \
  --listen :6789 \
  --serving_table ${tmp}/serving


echo ${tmp}
