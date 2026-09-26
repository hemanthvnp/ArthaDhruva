/** Three ascending bars in the risk-spectrum colours: the product's mark. The same green, amber and
 * red carry meaning everywhere else in the UI, so the logo teaches the colour language. */
export default function BrandMark({ size = 30 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true">
      <rect x="3" y="17" width="7" height="12" rx="2" fill="var(--sig-low)" />
      <rect x="12.5" y="10" width="7" height="19" rx="2" fill="var(--sig-mid)" />
      <rect x="22" y="3" width="7" height="26" rx="2" fill="var(--sig-high)" />
    </svg>
  );
}
