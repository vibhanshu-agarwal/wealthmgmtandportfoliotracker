declare module "fast-check" {
  // Re-export everything from the actual fast-check package.
  // This declaration exists solely to work around moduleResolution: "bundler"
  // not resolving fast-check's conditional `exports` map in some TS environments.
  export * from "fast-check/lib/fast-check";
  export { default } from "fast-check/lib/fast-check";
}
