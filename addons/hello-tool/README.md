# hello-tool (sample)

Reference Tier-2 add-on. It ships with the app (seeded from `app/src/main/assets/addons/`
into the on-device addons directory on first run) and proves the full loop:
**manifest → validate → init → register → invoke → result**, all visible in the Console tab.

- `addon.json` — the manifest (validated by the host before anything executes).
- `index.js` — what the host actually runs (IIFE, no ESM).
- `src/index.ts` — typed source; rebuild with
  `npx esbuild src/index.ts --bundle --format=iife --outfile=index.js`.
