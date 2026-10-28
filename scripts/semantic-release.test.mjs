import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Writable } from "node:stream";
import { fileURLToPath, pathToFileURL } from "node:url";
import test from "node:test";
import semanticRelease from "semantic-release";
import config from "../release.config.mjs";

test("versions and notes contain only release-worthy commits since the last version", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "helmfile-semantic-test-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const cwd = join(directory, "checkout");
  const remote = join(directory, "remote.git");
  const env = {
    PATH: process.env.PATH,
    HOME: process.env.HOME,
    GIT_CONFIG_GLOBAL: "/dev/null",
    GIT_CONFIG_SYSTEM: "/dev/null",
  };
  const git = (args, location = cwd) => execFileSync("git", args, { cwd: location, env, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
  git(["init", "--bare", "-b", "main", remote], directory);
  git(["init", "-b", "main", cwd], directory);
  git(["config", "user.name", "Release Test"]);
  git(["config", "user.email", "release-test@example.invalid"]);
  git(["commit", "--allow-empty", "-m", "feat: already released capability"]);
  git(["tag", "v0.5.3"]);
  git(["remote", "add", "origin", remote]);
  git(["push", "--set-upstream", "origin", "main", "--tags"]);
  const output = new Writable({ write(_chunk, _encoding, callback) { callback(); } });
  const release = () => semanticRelease({
    ...config,
    repositoryUrl: pathToFileURL(remote).href,
    plugins: [...config.plugins.slice(0, 2), fileURLToPath(new URL("./marketplace-release.mjs", import.meta.url))],
    dryRun: true,
    ci: false,
  }, { cwd, env, stdout: output, stderr: output });
  const commit = (message) => {
    git(["commit", "--allow-empty", "-m", message]);
    git(["push", "origin", "main"]);
  };

  commit("docs: clarify environment selection");
  assert.equal(await release(), false, "documentation alone must not publish a version");
  commit("fix(navigation): open the requested chart version");
  let result = await release();
  assert.equal(result.lastRelease.version, "0.5.3");
  assert.equal(result.nextRelease.version, "0.5.4");
  assert.match(result.nextRelease.notes, /open the requested chart version/);
  assert.doesNotMatch(result.nextRelease.notes, /already released capability|clarify environment selection/);

  // Simulate the successful release boundary, then check the next delta.
  git(["tag", "v0.5.4"]);
  git(["push", "origin", "v0.5.4"]);
  commit("feat(completion): suggest repository chart names");
  result = await release();
  assert.equal(result.lastRelease.version, "0.5.4");
  assert.equal(result.nextRelease.version, "0.6.0");
  assert.match(result.nextRelease.notes, /suggest repository chart names/);
  assert.doesNotMatch(result.nextRelease.notes, /open the requested chart version|already released capability/);

  commit("feat(settings)!: require explicit protected environment patterns\n\nBREAKING CHANGE: Configure a protected environment pattern before running commands.");
  result = await release();
  assert.equal(result.nextRelease.version, "1.0.0");
  assert.match(result.nextRelease.notes, /BREAKING CHANGES|⚠ BREAKING CHANGES/);
  assert.match(result.nextRelease.notes, /Configure a protected environment pattern/);
  assert.equal(git(["tag", "--list", "v1.0.0"]), "", "dry runs must not create release tags");
});
