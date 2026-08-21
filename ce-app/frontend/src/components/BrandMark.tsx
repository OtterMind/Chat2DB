/**
 * Wordmark in the spirit of the Breaking Bad logo: the full product name is
 * spelled out, but the initials sit inside periodic-table style element boxes.
 *
 *   [ C ] utting  [ E ] dge
 *     6             99
 */
export default function BrandMark({ size = 'md' }: { size?: 'sm' | 'md' | 'lg' }) {
  return (
    <span className={`ce-brand ce-brand--${size}`} title="Cutting Edge" dir="ltr">
      <span className="ce-brand__el ce-brand__el--c">
        <span className="ce-brand__num">6</span>
        <span className="ce-brand__sym">C</span>
      </span>
      <span className="ce-brand__word">utting</span>
      <span className="ce-brand__el ce-brand__el--e">
        <span className="ce-brand__num">99</span>
        <span className="ce-brand__sym">E</span>
      </span>
      <span className="ce-brand__word">dge</span>
    </span>
  )
}
