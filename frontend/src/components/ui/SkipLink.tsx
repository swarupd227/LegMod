/**
 * Keyboard users (and screen readers) get a "Skip to main content" link
 * that focuses #main-content when activated. Hidden until focused.
 */
export function SkipLink() {
  return (
    <a
      href="#main-content"
      className="sr-only-focusable"
    >
      Skip to main content
    </a>
  );
}
