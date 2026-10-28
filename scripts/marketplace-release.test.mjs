import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { prepare, publish, releaseArguments, verifyConditions, writeReleaseNotes } from "./marketplace-release.mjs";

async function temporaryContext(t) {
  const cwd = await mkdtemp(join(tmpdir(), "helmfile-marketplace-test-"));
  t.after(() => rm(cwd, { recursive: true, force: true }));
  return {
    cwd,
    env: { ...process.env, PUBLISH_TOKEN: "test-only" },
    options: {},
    nextRelease: { version: "0.5.4", gitTag: "v0.5.4", notes: "### Bug Fixes\n\n* Fix **chart navigation**.\n" },
  };
}

test("publishing validates credentials and optional signing before preparation", () => {
  assert.throws(() => verifyConditions({}, { env: {}, options: {} }), /PUBLISH_TOKEN/);
  assert.throws(() => verifyConditions({}, { env: { PUBLISH_TOKEN: "test", PRIVATE_KEY: "key" }, options: {} }), /both/);
  assert.doesNotThrow(() => verifyConditions({}, { env: { PUBLISH_TOKEN: "test" }, options: {} }));
  assert.doesNotThrow(() => verifyConditions({}, { env: {}, options: { dryRun: true } }));
});

test("notes use ignored build output, format Markdown, and escape raw HTML", async (t) => {
  const context = await temporaryContext(t);
  context.nextRelease.notes += '\n<script>alert("commit subject")</script>\n';
  await writeReleaseNotes(context);
  const html = await readFile(join(context.cwd, "build/release/notes.html"), "utf8");
  assert.match(html, /<h3>Bug Fixes<\/h3>/);
  assert.match(html, /<strong>chart navigation<\/strong>/);
  assert.match(html, /&lt;script&gt;/);
  assert.doesNotMatch(html, /<script>/);
  assert.deepEqual(await readdir(context.cwd), ["build"]);
  await assert.rejects(writeReleaseNotes({ ...context, nextRelease: { ...context.nextRelease, notes: " " } }), /did not generate/);
});

test("Gradle receives an exact version and notes path as arguments, never commit text", async (t) => {
  const context = await temporaryContext(t);
  context.nextRelease.notes = "$(touch injected) `shell` 'quotes'\nnew line";
  const arguments_ = releaseArguments(context);
  assert.ok(arguments_.includes("-PpluginVersion=0.5.4"));
  assert.ok(arguments_.includes(`-PreleaseNotesFile=${join(context.cwd, "build/release/notes.html")}`));
  assert.ok(arguments_.includes("--no-configuration-cache"));
  assert.ok(arguments_.every((argument) => !argument.includes("touch injected")));
  assert.throws(() => releaseArguments({ ...context, nextRelease: { version: "0.5.4;bad", gitTag: "v0.5.4" } }), /stable/);
  assert.throws(() => releaseArguments({ ...context, nextRelease: { version: "0.5.4", gitTag: "v0.5.5" } }), /matching/);
});

async function fakeGradle(context) {
  await writeFile(join(context.cwd, "gradlew"), `#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
fs.appendFileSync('gradle-calls.jsonl', JSON.stringify({ args, tag: process.env.RELEASE_TAG }) + '\\n');
if (process.env.FAIL_GRADLE) process.exit(7);
if (args.includes('buildPlugin')) {
  fs.mkdirSync('build/distributions', { recursive: true });
  fs.writeFileSync('build/distributions/helmfile-intellij-plugin-0.5.4.zip', 'fixture archive');
}
`, { mode: 0o755 });
}

test("release preparation verifies the plugin and publishing reuses identical versioned notes", async (t) => {
  const context = await temporaryContext(t);
  context.env.PRIVATE_KEY = "test-key";
  context.env.CERTIFICATE_CHAIN = "test-chain";
  await fakeGradle(context);
  await mkdir(join(context.cwd, "build/distributions"), { recursive: true });
  await writeFile(join(context.cwd, "build/distributions/stale.zip"), "old archive");
  await prepare({}, context);
  assert.deepEqual(await readdir(join(context.cwd, "build/distributions")), ["helmfile-intellij-plugin-0.5.4.zip"]);
  const before = await readFile(join(context.cwd, "build/release/notes.html"), "utf8");
  const result = await publish({}, context);
  assert.match(result.url, /plugins.jetbrains.com/);
  const calls = (await readFile(join(context.cwd, "gradle-calls.jsonl"), "utf8")).trim().split("\n").map(JSON.parse);
  for (const task of ["verifyReleaseVersion", "test", "buildPlugin", "verifyPluginStructure", "verifyPlugin", "verifyPluginSignature"]) {
    assert.ok(calls[0].args.includes(task), task);
  }
  assert.deepEqual(calls[0].args.slice(0, 4), calls[1].args.slice(0, 4));
  assert.equal(calls[1].args.at(-1), "publishPlugin");
  assert.ok(calls.every((call) => call.tag === "v0.5.4"));
  assert.equal(await readFile(join(context.cwd, "build/release/notes.html"), "utf8"), before);
});

test("a failed Gradle verification aborts release preparation", async (t) => {
  const context = await temporaryContext(t);
  context.env.FAIL_GRADLE = "1";
  await fakeGradle(context);
  await assert.rejects(prepare({}, context), /exit 7/);
  const calls = (await readFile(join(context.cwd, "gradle-calls.jsonl"), "utf8")).trim().split("\n").map(JSON.parse);
  assert.equal(calls.length, 1);
  assert.ok(!calls[0].args.includes("publishPlugin"));
});
