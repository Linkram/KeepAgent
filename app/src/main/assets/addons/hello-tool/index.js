/*
 * hello-tool — sample Tier-2 add-on (M0).
 *
 * Runs in the quickjs-ng sandbox. The only host surface available is the
 * `ka` object provided by the host prelude (docs/ADDON_API.md):
 *
 *   ka.log(msg)                 - append to the host event stream
 *   ka.workspacePath()          - active workspace root (string)
 *   ka.settingsGet(namespace)   - JSON settings document for a namespace, or null
 *   ka.registerTool(spec)       - register a tool with the host
 *
 * Tool handlers are synchronous in M0 (async handlers land in M1).
 */
(function () {
  "use strict";

  ka.log("hello-tool: module loaded in sandbox");

  ka.registerTool({
    name: "hello",
    description: "Sample tool: returns a greeting. Proves the Tier-2 loop (manifest -> validate -> init -> invoke).",
    permission: "read",
    inputSchema: {
      type: "object",
      properties: {
        name: { type: "string", description: "Name to greet" }
      },
      required: ["name"]
    },
    handler: function (args) {
      return {
        text: "Hello, " + args.name +
              ". The Tier-2 add-on loop is working end to end (quickjs-ng sandbox -> host -> sandbox)."
      };
    }
  });
})();
