// Copyright (C) 2014 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gitiles;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.io.BaseEncoding;
import com.google.common.net.HttpHeaders;
import com.google.gitiles.FileJsonData.File;
import com.google.gitiles.TreeJsonData.Tree;
import com.google.template.soy.data.SoyListData;
import com.google.template.soy.data.restricted.StringData;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.eclipse.jgit.dircache.DirCacheEditor.PathEdit;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevBlob;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@PathServlet}. */
@SuppressWarnings("unchecked")
@RunWith(JUnit4.class)
public class PathServletTest extends ServletTest {

  private String stackOverflowContent =
      "S:  Maintained\n"
          + "USB W996[87]CF DRIVER\n"
          + "P:  Luca Risolia\n"
          + "M:  luca.risolia@studio.unibo.it\n"
          + "L:  linux-usb-devel@lists.sourceforge.net\n"
          + "L:  video4linux-list@redhat.com\n"
          + "W:  http://www.linux-projects.org\n"
          + "S:  Maintained\n"
          + "USB ZC0301 DRIVER\n"
          + "P:  Luca Risolia\n"
          + "M:  luca.risolia@studio.unibo.it\n"
          + "L:  linux-usb-devel@lists.sourceforge.net\n"
          + "L:  video4linux-list@redhat.com\n"
          + "W:  http://www.linux-projects.org\n"
          + "S:  Maintained\n"
          + "USB ZD1201 DRIVER\n"
          + "P:  Jeroen Vreeken\n"
          + "M:  pe1rxq@amsat.org\n"
          + "L:  linux-usb-users@lists.sourceforge.net\n"
          + "L:  linux-usb-devel@lists.sourceforge.net\n"
          + "W:  http://linux-lc100020.sourceforge.net\n"
          + "S:  Maintained\n"
          + "USER-MODE LINUX\n"
          + "P:  Jeff Dike\n"
          + "M:  jdike@karaya.com\n"
          + "L:  user-mode-linux-devel@lists.sourceforge.net\n"
          + "L:  user-mode-linux-user@lists.sourceforge.net\n"
          + "W:  http://user-mode-linux.sourceforge.net\n"
          + "S:  Maintained\n"
          + "    \n"
          + "FAT/VFAT/MSDOS FILESYSTEM:\n"
          + "P:  OGAWA Hirofumi\n"
          + "M:  hirofumi@mail.parknet.co.jp\n"
          + "L:  linux-kernel@vger.kernel.org\n"
          + "S:  Maintained\n"
          + "VIA 82Cxxx AUDIO DRIVER (old OSS driver)\n"
          + "P:  Jeff Garzik\n"
          + "S:  Odd fixes\n"
          + "VIA RHINE NETWORK DRIVER\n"
          + "P:  Roger Luethi\n"
          + "M:  rl@hellgate.ch\n"
          + "S:  Maintained\n"
          + "VIAPRO SMBUS DRIVER\n"
          + "P:  Jean Delvare\n"
          + "M:  khali@linux-fr.org\n"
          + "L:  i2c@lm-sensors.org\n"
          + "S:  Maintained\n"
          + "UCLINUX (AND M68KNOMMU)\n"
          + "P:  Greg Ungerer\n"
          + "M:  gerg@uclinux.org\n"
          + "W:  http://www.uclinux.org/\n"
          + "L:  uclinux-dev@uclinux.org  (subscribers-only)\n"
          + "S:  Maintained\n"
          + "UCLINUX FOR NEC V850\n"
          + "P:  Miles Bader\n"
          + "M:  uclinux-v850@lsi.nec.co.jp\n"
          + "W:  http://www.ic.nec.co.jp/micro/uclinux/eng/\n"
          + "W:  http://www.ee.nec.de/uclinux/\n"
          + "S:  Supported\n"
          + "UCLINUX FOR RENESAS H8/300\n"
          + "P:  Yoshinori Sato\n"
          + "M:  ysato@users.sourceforge.jp\n"
          + "W:  http://uclinux-h8.sourceforge.jp/\n"
          + "S:  Supported\n"
          + "USB DIAMOND RIO500 DRIVER\n"
          + "P:  Cesar Miquel\n"
          + "M:  miquel@df.uba.ar\n"
          + "L:  rio500-users@lists.sourceforge.net\n"
          + "W:  http://rio500.sourceforge.net\n"
          + "S:  Maintained\n"
          + "VIDEO FOR LINUX\n"
          + "P:  Mauro Carvalho Chehab\n"
          + "M:  mchehab@infradead.org\n"
          + "M:  v4l-dvb-maintainer@linuxtv.org\n"
          + "L:  video4linux-list@redhat.com\n"
          + "W:  http://linuxtv.org\n"
          + "T:  git kernel.org:/pub/scm/linux/kernel/git/mchehab/v4l-dvb.git\n"
          + "S:  Maintained\n"
          + "VT1211 HARDWARE MONITOR DRIVER\n"
          + "P:  Juerg Haefliger\n"
          + "M:  juergh@gmail.com\n"
          + "L:  lm-sensors@lm-sensors.org\n"
          + "S:  Maintained\n"
          + "VT8231 HARDWARE MONITOR DRIVER\n"
          + "P:  Roger Lucas\n"
          + "M:  roger@planbit.co.uk\n"
          + "L:  lm-sensors@lm-sensors.org\n"
          + "S:  Maintained\n"
          + "W1 DALLAS'S 1-WIRE BUS\n"
          + "P:  Evgeniy Polyakov\n"
          + "M:  johnpol@2ka.mipt.ru\n"
          + "S:  Maintained\n"
          + "W83791D HARDWARE MONITORING DRIVER\n"
          + "P:  Charles Spirakis\n"
          + "M:  bezaur@gmail.com\n"
          + "L:  lm-sensors@lm-sensors.org\n"
          + "S:  Maintained\n"
          + "W83L51xD SD/MMC CARD INTERFACE DRIVER\n"
          + "P:  Pierre Ossman\n"
          + "M:  drzeus-wbsd@drzeus.cx\n"
          + "L:  wbsd-devel@list.drzeus.cx\n"
          + "W:  http://projects.drzeus.cx/wbsd\n"
          + "S:  Maintained\n"
          + "WATCHDOG DEVICE DRIVERS\n"
          + "P:  Wim Van Sebroeck\n"
          + "M:  wim@iguana.be\n"
          + "T:  git kernel.org:/pub/scm/linux/kernel/git/wim/linux-2.6-watchdog.git\n"
          + "S:  Maintained\n"
          + "WAVELAN NETWORK DRIVER & WIRELESS EXTENSIONS\n"
          + "P:  Jean Tourrilhes\n"
          + "M:  jt@hpl.hp.com\n"
          + "W:  http://www.hpl.hp.com/personal/Jean_Tourrilhes/Linux/\n"
          + "S:  Maintained\n"
          + "WD7000 SCSI DRIVER\n"
          + "P:  Miroslav Zagorac\n"
          + "M:  zaga@fly.cc.fer.hr\n"
          + "L:  linux-scsi@vger.kernel.org\n"
          + "S:  Maintained\n"
          + "WISTRON LAPTOP BUTTON DRIVER\n"
          + "P:  Miloslav Trmac\n"
          + "M:  mitr@volny.cz\n"
          + "S:  Maintained\n"
          + "WL3501 WIRELESS PCMCIA CARD DRIVER\n"
          + "P:  Arnaldo Carvalho de Melo\n"
          + "M:  acme@conectiva.com.br\n"
          + "W:  http://advogato.org/person/acme\n"
          + "S:  Maintained\n"
          + "X.25 NETWORK LAYER\n"
          + "P:  Henner Eisen\n"
          + "M:  eis@baty.hanse.de\n"
          + "L:  linux-x25@vger.kernel.org\n"
          + "S:  Maintained\n"
          + "XFS FILESYSTEM\n"
          + "P:  Silicon Graphics Inc\n"
          + "P:  Tim Shimmin, David Chatterton\n"
          + "M:  xfs-masters@oss.sgi.com\n"
          + "L:  xfs@oss.sgi.com\n"
          + "W:  http://oss.sgi.com/projects/xfs\n"
          + "T:  git git://oss.sgi.com:8090/xfs/xfs-2.6\n"
          + "S:  Supported\n"
          + "X86 3-LEVEL PAGING (PAE) SUPPORT\n"
          + "P:  Ingo Molnar\n"
          + "M:  mingo@redhat.com\n"
          + "S:  Maintained\n"
          + "X86-64 port\n"
          + "P:  Andi Kleen\n"
          + "M:  ak@suse.de\n"
          + "L:  discuss@x86-64.org\n"
          + "W:  http://www.x86-64.org\n"
          + "S:  Maintained\n"
          + "YAM DRIVER FOR AX.25\n"
          + "P:  Jean-Paul Roubelat\n"
          + "M:  jpr@f6fbb.org\n"
          + "L:  linux-hams@vger.kernel.org\n"
          + "S:  Maintained\n"
          + "YEALINK PHONE DRIVER\n"
          + "P:  Henk Vergonet\n"
          + "M:  Henk.Vergonet@gmail.com\n"
          + "L:  usbb2k-api-dev@nongnu.org\n"
          + "S:  Maintained\n"
          + "Z8530 DRIVER FOR AX.25\n"
          + "P:  Joerg Reuter\n"
          + "M:  jreuter@yaina.de\n"
          + "W:  http://yaina.de/jreuter/\n"
          + "W:  http://www.qsl.net/dl1bke/\n"
          + "L:  linux-hams@vger.kernel.org\n"
          + "S:  Maintained\n"
          + "ZD1211RW WIRELESS DRIVER\n"
          + "P:  Daniel Drake\n"
          + "M:  dsd@gentoo.org\n"
          + "P:  Ulrich Kunitz\n"
          + "M:  kune@deine-taler.de\n"
          + "W:  http://zd1211.ath.cx/wiki/DriverRewrite\n"
          + "L:  zd1211-devs@lists.sourceforge.net (subscribers-only)\n"
          + "S:  Maintained\n"
          + "ZF MACHZ WATCHDOG\n"
          + "P:  Fernando Fuganti\n"
          + "M:  fuganti@netbank.com.br\n"
          + "W:  http://cvs.conectiva.com.br/drivers/ZFL-watchdog/\n"
          + "S:  Maintained\n"
          + "ZR36067 VIDEO FOR LINUX DRIVER\n"
          + "P:  Ronald Bultje\n"
          + "M:  rbultje@ronald.bitfreak.net\n"
          + "L:  mjpeg-users@lists.sourceforge.net\n"
          + "W:  http://mjpeg.sourceforge.net/driver-zoran/\n"
          + "S:  Maintained\n"
          + "ZR36120 VIDEO FOR LINUX DRIVER\n"
          + "P:  Pauline Middelink\n"
          + "M:  middelin@polyware.nl\n"
          + "W:  http://www.polyware.nl/~middelin/En/hobbies.html\n"
          + "W:  http://www.polyware.nl/~middelin/hobbies.html\n"
          + "S:  Maintained\n"
          + "THE REST\n"
          + "P:  Linus Torvalds\n"
          + "S:  Buried alive in reporters";

  @Test
  public void rootTreeHtml() throws Exception {
    repo.branch("master").commit().add("foo", "contents").create();

    Map<String, ?> data = buildData("/repo/+/master/");
    assertThat(data).containsEntry("type", "TREE");
    List<Map<String, ?>> entries = getTreeEntries(data);
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).get("name")).isEqualTo("foo");
  }

  @Test
  public void subTreeHtml() throws Exception {
    repo.branch("master")
        .commit()
        .add("foo/bar", "bar contents")
        .add("baz", "baz contents")
        .create();

    Map<String, ?> data = buildData("/repo/+/master/");
    assertThat(data).containsEntry("type", "TREE");
    List<Map<String, ?>> entries = getTreeEntries(data);
    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).get("name")).isEqualTo("baz");
    assertThat(entries.get(1).get("name")).isEqualTo("foo/");

    data = buildData("/repo/+/master/foo");
    assertThat(data).containsEntry("type", "TREE");
    entries = getTreeEntries(data);
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).get("name")).isEqualTo("bar");

    data = buildData("/repo/+/master/foo/");
    assertThat(data).containsEntry("type", "TREE");
    entries = getTreeEntries(data);
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).get("name")).isEqualTo("bar");
  }

  @Test
  public void fileHtml() throws Exception {
    repo.branch("master").commit().add("foo", "foo\ncontents\n").create();

    Map<String, ?> data = buildData("/repo/+/master/foo");
    assertThat(data).containsEntry("type", "REGULAR_FILE");

    SoyListData lines = (SoyListData) getBlobData(data).get("lines");
    assertThat(lines.length()).isEqualTo(2);

    SoyListData spans = lines.getListData(0);
    assertThat(spans.length()).isEqualTo(1);
    assertThat(spans.getMapData(0).get("classes")).isEqualTo(StringData.forValue("pln"));
    assertThat(spans.getMapData(0).get("text")).isEqualTo(StringData.forValue("foo"));

    spans = lines.getListData(1);
    assertThat(spans.length()).isEqualTo(1);
    assertThat(spans.getMapData(0).get("classes")).isEqualTo(StringData.forValue("pln"));
    assertThat(spans.getMapData(0).get("text")).isEqualTo(StringData.forValue("contents"));
  }

  @Test
  public void fileWithMaxLines() throws Exception {
    int MAX_LINE_COUNT = 50000;
    StringBuilder contentBuilder = new StringBuilder();
    for (int i = 1; i < MAX_LINE_COUNT; i++) {
      contentBuilder.append("\n");
    }
    repo.branch("master").commit().add("bar", contentBuilder.toString()).create();

    Map<String, ?> data = buildData("/repo/+/master/bar");
    SoyListData lines = (SoyListData) getBlobData(data).get("lines");
    assertThat(lines.length()).isEqualTo(MAX_LINE_COUNT - 1);
  }

  @Test
  public void fileLargerThanSupportedLines() throws Exception {
    int MAX_LINE_COUNT = 50000;
    StringBuilder contentBuilder = new StringBuilder();
    for (int i = 1; i <= MAX_LINE_COUNT; i++) {
      contentBuilder.append("\n");
    }
    repo.branch("master").commit().add("largebar", contentBuilder.toString()).create();

    Map<String, ?> data = buildData("/repo/+/master/largebar");
    SoyListData lines = (SoyListData) getBlobData(data).get("lines");
    assertThat(lines).isNull();
  }

  @Test
  public void largeFileHtml() throws Exception {
    int largeContentSize = BlobSoyData.MAX_FILE_SIZE + 1;
    repo.branch("master").commit().add("foo", generateContent(largeContentSize)).create();

    Map<String, ?> data = (Map<String, ?>) buildData("/repo/+/master/foo").get("data");
    assertThat(data).containsEntry("lines", null);
    assertThat(data).containsEntry("size", "" + largeContentSize);
  }

  @Test
  public void contentParsedWithBatches() throws Exception {
    repo.branch("master").commit().add("foo", stackOverflowContent).create();

    Map<String, ?> data = (Map<String, ?>) buildData("/repo/+/master/foo").get("data");
    SoyListData lines = (SoyListData) data.get("lines");
    assertThat(lines.length()).isNotNull();
    assertThat(data.get("size")).isNull();

    SoyListData spans = lines.getListData(0);
    assertThat(spans.length()).isEqualTo(4);
    assertThat(spans.getMapData(3).get("classes")).isEqualTo(StringData.forValue("typ"));
    assertThat(spans.getMapData(3).get("text")).isEqualTo(StringData.forValue("Maintained"));
  }

  @Test
  public void moreThanOneBatchHtml() throws Exception {
    int twoBatchesContentSize = BlobSoyData.NUMBER_OF_LINES_PER_BATCH * 2;
    repo.branch("master").commit().add("foo", generateLines(twoBatchesContentSize, 500)).create();

    Map<String, ?> data = (Map<String, ?>) buildData("/repo/+/master/foo").get("data");
    assertThat(data.get("lines")).isNotNull();
    SoyListData lines = (SoyListData) data.get("lines");
    assertThat(lines.length()).isEqualTo(twoBatchesContentSize);
    assertThat(data.get("size")).isNull();
  }

  private static String generateLines(int numberOfLines, final int contentSize) {

    return IntStream.range(0, numberOfLines)
        .boxed()
        .map(i -> generateContent(contentSize))
        .collect(Collectors.joining("\n"));
  }

  private static String generateContent(int contentSize) {
    char[] str = new char[contentSize];
    for (int i = 0; i < contentSize; i++) {
      str[i] = (char) ('0' + (i % 78));
    }
    return new String(str);
  }

  @Test
  public void symlinkHtml() throws Exception {
    testSymlink("foo", "bar", "foo");
  }

  @Test
  public void relativeSymlinkHtml() throws Exception {
    testSymlink("foo/bar", "foo/baz", "./bar");
  }

  @Test
  public void gitlinkHtml() throws Exception {
    String gitmodules =
        "[submodule \"gitiles\"]\n"
            + "  path = gitiles\n"
            + "  url = https://gerrit.googlesource.com/gitiles\n";
    final String gitilesSha = "2b2f34bba3c2be7e2506ce6b1f040949da350cf9";
    repo.branch("master")
        .commit()
        .add(".gitmodules", gitmodules)
        .edit(
            new PathEdit("gitiles") {
              @Override
              public void apply(DirCacheEntry ent) {
                ent.setFileMode(FileMode.GITLINK);
                ent.setObjectId(ObjectId.fromString(gitilesSha));
              }
            })
        .create();

    Map<String, ?> data = buildData("/repo/+/master/gitiles");
    assertThat(data).containsEntry("type", "GITLINK");

    Map<String, ?> linkData = getBlobData(data);
    assertThat(linkData).containsEntry("sha", gitilesSha);
    assertThat(linkData).containsEntry("remoteUrl", "https://gerrit.googlesource.com/gitiles");
    assertThat(linkData).containsEntry("httpUrl", "https://gerrit.googlesource.com/gitiles");
  }

  @Test
  public void blobText() throws Exception {
    repo.branch("master").commit().add("foo", "contents").create();
    String text = buildBlob("/repo/+/master/foo", "100644");
    assertThat(text).isEqualTo("contents");
  }

  @Test
  public void fileJson() throws Exception {
    RevBlob blob = repo.blob("contents");
    repo.branch("master").commit().add("path/to/file", blob).create();

    File file = buildJson(File.class, "/repo/+/master/path/to/file");

    assertThat(file.id).isEqualTo(blob.name());
    assertThat(file.repo).isEqualTo("repo");
    assertThat(file.revision).isEqualTo("master");
    assertThat(file.path).isEqualTo("path/to/file");
  }

  @Test
  public void symlinkText() throws Exception {
    final RevBlob link = repo.blob("foo");
    repo.branch("master")
        .commit()
        .edit(
            new PathEdit("baz") {
              @Override
              public void apply(DirCacheEntry ent) {
                ent.setFileMode(FileMode.SYMLINK);
                ent.setObjectId(link);
              }
            })
        .create();
    String text = buildBlob("/repo/+/master/baz", "120000");
    assertThat(text).isEqualTo("foo");
  }

  @Test
  public void treeText() throws Exception {
    RevBlob blob = repo.blob("contents");
    RevTree tree = repo.tree(repo.file("foo/bar", blob));
    repo.branch("master").commit().setTopLevelTree(tree).create();

    String expected = "040000 tree " + repo.get(tree, "foo").name() + "\tfoo\n";
    assertThat(buildBlob("/repo/+/master/", "040000")).isEqualTo(expected);

    expected = "100644 blob " + blob.name() + "\tbar\n";
    assertThat(buildBlob("/repo/+/master/foo", "040000")).isEqualTo(expected);
    assertThat(buildBlob("/repo/+/master/foo/", "040000")).isEqualTo(expected);
  }

  @Test
  public void treeTextEscaped() throws Exception {
    RevBlob blob = repo.blob("contents");
    repo.branch("master").commit().add("foo\nbar\rbaz", blob).create();

    assertThat(buildBlob("/repo/+/master/", "040000"))
        .isEqualTo("100644 blob " + blob.name() + "\t\"foo\\nbar\\rbaz\"\n");
  }

  @Test
  public void nonBlobText() throws Exception {
    String gitmodules =
        "[submodule \"gitiles\"]\n"
            + "  path = gitiles\n"
            + "  url = https://gerrit.googlesource.com/gitiles\n";
    final String gitilesSha = "2b2f34bba3c2be7e2506ce6b1f040949da350cf9";
    repo.branch("master")
        .commit()
        .add("foo/bar", "contents")
        .add(".gitmodules", gitmodules)
        .edit(
            new PathEdit("gitiles") {
              @Override
              public void apply(DirCacheEntry ent) {
                ent.setFileMode(FileMode.GITLINK);
                ent.setObjectId(ObjectId.fromString(gitilesSha));
              }
            })
        .create();

    assertNotFound("/repo/+/master/nonexistent", "format=text");
    assertNotFound("/repo/+/master/gitiles", "format=text");
  }

  @Test
  public void treeJsonSizes() throws Exception {
    RevCommit c = repo.parseBody(repo.branch("master").commit().add("baz", "01234567").create());

    Tree tree = buildJson(Tree.class, "/repo/+/master/", "long=1");

    assertThat(tree.id).isEqualTo(c.getTree().name());
    assertThat(tree.entries).hasSize(1);
    assertThat(tree.entries.get(0).mode).isEqualTo(0100644);
    assertThat(tree.entries.get(0).type).isEqualTo("blob");
    assertThat(tree.entries.get(0).name).isEqualTo("baz");
    assertThat(tree.entries.get(0).size).isEqualTo(8);
  }

  @Test
  public void treeJsonLinkTarget() throws Exception {
    final ObjectId targetID = repo.blob("target");
    RevCommit c =
        repo.parseBody(
            repo.branch("master")
                .commit()
                .edit(
                    new PathEdit("link") {
                      @Override
                      public void apply(DirCacheEntry ent) {
                        ent.setFileMode(FileMode.SYMLINK);
                        ent.setObjectId(targetID);
                      }
                    })
                .create());

    Tree tree = buildJson(Tree.class, "/repo/+/master/", "long=1");

    assertThat(tree.id).isEqualTo(c.getTree().name());
    assertThat(tree.entries).hasSize(1);

    TreeJsonData.Entry e = tree.entries.get(0);
    assertThat(e.mode).isEqualTo(0120000);
    assertThat(e.type).isEqualTo("blob");
    assertThat(e.name).isEqualTo("link");
    assertThat(e.id).isEqualTo(targetID.name());
    assertThat(e.target).isEqualTo("target");
  }

  @Test
  public void treeJsonRecursive() throws Exception {
    RevCommit c =
        repo.parseBody(
            repo.branch("master")
                .commit()
                .add("foo/baz/bar/a", "bar contents")
                .add("foo/baz/bar/b", "bar contents")
                .add("baz", "baz contents")
                .create());
    Tree tree = buildJson(Tree.class, "/repo/+/master/", "recursive=1");

    assertThat(tree.id).isEqualTo(c.getTree().name());
    assertThat(tree.entries).hasSize(3);

    assertThat(tree.entries.get(0).name).isEqualTo("baz");
    assertThat(tree.entries.get(1).name).isEqualTo("foo/baz/bar/a");
    assertThat(tree.entries.get(2).name).isEqualTo("foo/baz/bar/b");

    tree = buildJson(Tree.class, "/repo/+/master/foo/baz", "recursive=1");

    assertThat(tree.entries).hasSize(2);

    assertThat(tree.entries.get(0).name).isEqualTo("bar/a");
    assertThat(tree.entries.get(1).name).isEqualTo("bar/b");
  }

  @Test
  public void treeJson() throws Exception {
    RevCommit c =
        repo.parseBody(
            repo.branch("master")
                .commit()
                .add("foo/bar", "bar contents")
                .add("baz", "baz contents")
                .create());

    Tree tree = buildJson(Tree.class, "/repo/+/master/");
    assertThat(tree.id).isEqualTo(c.getTree().name());
    assertThat(tree.entries).hasSize(2);
    assertThat(tree.entries.get(0).mode).isEqualTo(0100644);
    assertThat(tree.entries.get(0).type).isEqualTo("blob");
    assertThat(tree.entries.get(0).id).isEqualTo(repo.get(c.getTree(), "baz").name());
    assertThat(tree.entries.get(0).name).isEqualTo("baz");
    assertThat(tree.entries.get(1).mode).isEqualTo(040000);
    assertThat(tree.entries.get(1).type).isEqualTo("tree");
    assertThat(tree.entries.get(1).id).isEqualTo(repo.get(c.getTree(), "foo").name());
    assertThat(tree.entries.get(1).name).isEqualTo("foo");

    tree = buildJson(Tree.class, "/repo/+/master/foo");
    assertThat(tree.id).isEqualTo(repo.get(c.getTree(), "foo").name());
    assertThat(tree.entries).hasSize(1);
    assertThat(tree.entries.get(0).mode).isEqualTo(0100644);
    assertThat(tree.entries.get(0).type).isEqualTo("blob");
    assertThat(tree.entries.get(0).id).isEqualTo(repo.get(c.getTree(), "foo/bar").name());
    assertThat(tree.entries.get(0).name).isEqualTo("bar");

    tree = buildJson(Tree.class, "/repo/+/master/foo/");
    assertThat(tree.id).isEqualTo(repo.get(c.getTree(), "foo").name());
    assertThat(tree.entries).hasSize(1);
    assertThat(tree.entries.get(0).mode).isEqualTo(0100644);
    assertThat(tree.entries.get(0).type).isEqualTo("blob");
    assertThat(tree.entries.get(0).id).isEqualTo(repo.get(c.getTree(), "foo/bar").name());
    assertThat(tree.entries.get(0).name).isEqualTo("bar");
  }

  @Test
  public void allowOrigin() throws Exception {
    repo.branch("master").commit().add("foo", "contents").create();
    FakeHttpServletResponse res = buildText("/repo/+/master/foo");
    assertThat(res.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .isEqualTo("http://localhost");
  }

  @Test
  public void rejectOrigin() throws Exception {
    repo.branch("master").commit().add("foo", "contents").create();
    FakeHttpServletResponse res =
        buildResponse("/repo/+/master/foo", "format=text", SC_OK, "http://notlocalhost");
    assertThat(res.getHeader(HttpHeaders.CONTENT_TYPE)).isEqualTo("text/plain");
    assertThat(res.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(null);
  }

  private void testSymlink(String linkTarget, String linkName, String linkContent)
      throws Exception {
    final RevBlob linkBlob = repo.blob(linkContent);
    repo.branch("master")
        .commit()
        .add(linkTarget, "contents")
        .edit(
            new PathEdit(linkName) {
              @Override
              public void apply(DirCacheEntry ent) {
                ent.setFileMode(FileMode.SYMLINK);
                ent.setObjectId(linkBlob);
              }
            })
        .create();

    Map<String, ?> data = buildData("/repo/+/master/" + linkName);
    assertThat(data).containsEntry("type", "SYMLINK");
    assertThat(getBlobData(data)).containsEntry("target", linkContent);
    assertThat(getBlobData(data)).containsEntry("targetUrl", "/b/repo/+/master/" + linkTarget);
  }

  private Map<String, ?> getBlobData(Map<String, ?> data) {
    return ((Map<String, Map<String, ?>>) data).get("data");
  }

  private List<Map<String, ?>> getTreeEntries(Map<String, ?> data) {
    return ((Map<String, List<Map<String, ?>>>) data.get("data")).get("entries");
  }

  private String buildBlob(String path, String expectedMode) throws Exception {
    FakeHttpServletResponse res = buildText(path);
    assertThat(res.getHeader(PathServlet.MODE_HEADER)).isEqualTo(expectedMode);
    String base64 = res.getActualBodyString();
    return new String(BaseEncoding.base64().decode(base64), UTF_8);
  }
}
