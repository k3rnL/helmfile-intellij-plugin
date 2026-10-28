import { spawn } from "node:child_process";
import { access, mkdir, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import MarkdownIt from "markdown-it";

const markdown = new MarkdownIt({ html: false });

export function verifyConditions(_config, { env, options }) {
  if (options.dryRun) return;
  if (!env.PUBLISH_TOKEN?.trim()) {
    throw new Error("Set the PUBLISH_TOKEN Actions secret before publishing to JetBrains Marketplace.");
  }
  if (Boolean(env.CERTIFICATE_CHAIN) !== Boolean(env.PRIVATE_KEY)) {
    throw new Error("Plugin signing requires both CERTIFICATE_CHAIN and PRIVATE_KEY, or neither.");
  }
}

export function releaseArguments({ cwd, nextRelease }) {
  if (!/^\d+\.\d+\.\d+$/.test(nextRelease.version) || nextRelease.gitTag !== `v${nextRelease.version}`) {
    throw new Error("Expected a stable semantic-release version and matching v-prefixed tag.");
  }
  return [
    "--no-daemon",
    "--no-configuration-cache",
    `-PpluginVersion=${nextRelease.version}`,
    `-PreleaseNotesFile=${join(cwd, "build/release/notes.html")}`,
  ];
}

export async function writeReleaseNotes({ cwd, nextRelease }) {
  if (!nextRelease.notes?.trim()) {
    throw new Error("semantic-release did not generate release notes for the commits being released.");
  }
  const directory = join(cwd, "build/release");
  await mkdir(directory, { recursive: true });
  await writeFile(join(directory, "notes.html"), markdown.render(nextRelease.notes));
}

function gradle(context, tasks) {
  return new Promise((resolve, reject) => {
    const child = spawn("./gradlew", [...releaseArguments(context), ...tasks], {
      cwd: context.cwd,
      env: { ...context.env, RELEASE_TAG: context.nextRelease.gitTag },
      stdio: "inherit",
    });
    child.once("error", reject);
    child.once("exit", (code, signal) => {
      if (code === 0) resolve();
      else reject(new Error(`Gradle ${tasks.join(" ")} failed (${signal || `exit ${code}`}).`));
    });
  });
}

export async function prepare(_config, context) {
  releaseArguments(context);
  await writeReleaseNotes(context);
  // Prevent an older local or CI archive from being attached to this release.
  await rm(join(context.cwd, "build/distributions"), { recursive: true, force: true });
  const tasks = [
    "verifyReleaseVersion", "test", "buildPlugin", "verifyPluginProjectConfiguration",
    "verifyPluginStructure", "verifyPlugin",
  ];
  if (context.env.PRIVATE_KEY) tasks.push("verifyPluginSignature");
  await gradle(context, tasks);
  await access(join(context.cwd, `build/distributions/helmfile-intellij-plugin-${context.nextRelease.version}.zip`));
}

export async function publish(_config, context) {
  await gradle(context, ["publishPlugin"]);
  return {
    name: "JetBrains Marketplace (awaiting approval)",
    url: "https://plugins.jetbrains.com/plugin/35395-helmfile",
  };
}
