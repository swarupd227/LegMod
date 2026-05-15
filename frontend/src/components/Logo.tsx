// Custom Atlas logomark — three layered planes evoking the migration arc:
// legacy substrate → reconciled middle → modern surface. Drawn at 24px;
// caller can scale via parent font-size or a wrapper.

export default function Logo({ size = 22, className = '' }: { size?: number; className?: string }) {
  return (
    <svg
      viewBox="0 0 28 28"
      width={size}
      height={size}
      className={className}
      aria-hidden="true"
    >
      <defs>
        <linearGradient id="atlas-g1" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%"  stopColor="#3D6BFF" />
          <stop offset="100%" stopColor="#1F4FD9" />
        </linearGradient>
      </defs>
      {/* base plane */}
      <path d="M3 19.5 L14 25 L25 19.5 L14 14 Z"
            fill="#1F4FD9" opacity="0.35" />
      {/* mid plane */}
      <path d="M3 14   L14 19.5 L25 14   L14 8.5 Z"
            fill="#1F4FD9" opacity="0.65" />
      {/* top plane */}
      <path d="M3 8.5  L14 14   L25 8.5  L14 3 Z"
            fill="url(#atlas-g1)" />
      {/* axis highlight */}
      <path d="M14 3 L14 25" stroke="white" strokeOpacity="0.18" strokeWidth="0.5" />
    </svg>
  );
}

export function LogoMark({ className = '' }: { className?: string }) {
  return (
    <div className={`flex items-center gap-2 ${className}`}>
      <Logo size={22} />
      <div className="flex items-baseline gap-1.5 leading-none">
        <span className="font-semibold tracking-tight text-white text-[15px]">Atlas</span>
        <span className="text-white/55 text-[13px] font-medium">Migrate</span>
      </div>
    </div>
  );
}
