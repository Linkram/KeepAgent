// hello-tool — typed source.
// Build: npx esbuild src/index.ts --bundle --format=iife --outfile=index.js
// (The shipped index.js is what the host loads; this file is the source of truth.)

/** M0: `ka` is provided by the host prelude (see docs/ADDON_API.md). */
declare const ka: {
  log(msg: string): void;
  workspacePath(): string;
  settingsGet(namespace: string): string | null;
  registerTool(spec: {
    name: string;
    description?: string;
    permission?: "read" | "write" | "exec" | "network" | "device";
    inputSchema?: object;
    handler: (args: any, ctx: { log: (m: string) => void; workspacePath: () => string }) => unknown;
  }): void;
};

ka.log("hello-tool: module loaded in sandbox");

ka.registerTool({
  name: "hello",
  description:
    "Sample tool: returns a greeting. Proves the Tier-2 loop (manifest -> validate -> init -> invoke).",
  permission: "read",
  inputSchema: {
    type: "object",
    properties: {
      name: { type: "string", description: "Name to greet" },
    },
    required: ["name"],
  },
  handler: (args: { name: string }) => ({
    text:
      `Hello, ${args.name}. ` +
      "The Tier-2 add-on loop is working end to end (quickjs-ng sandbox -> host -> sandbox).",
  }),
});
