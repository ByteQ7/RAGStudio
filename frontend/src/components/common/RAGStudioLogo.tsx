interface RAGStudioLogoProps {
  className?: string;
}

export function RAGStudioLogo({ className }: RAGStudioLogoProps) {
  return (
    <svg
      viewBox="0 0 32 32"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      className={className}
    >
      {/* Robot Head - larger, fills more of viewBox */}
      <rect x="4" y="7" width="24" height="19" rx="5" fill="currentColor" opacity="0.95" />

      {/* Antenna */}
      <line x1="16" y1="2" x2="16" y2="7" stroke="currentColor" strokeWidth="2" strokeLinecap="round" opacity="0.7" />
      <circle cx="16" cy="1" r="2" fill="currentColor" opacity="0.8" />

      {/* Eyes */}
      <rect x="9" y="13" width="5" height="5" rx="1.2" fill="white" opacity="0.9" />
      <rect x="18" y="13" width="5" height="5" rx="1.2" fill="white" opacity="0.9" />

      {/* Eye pupils */}
      <circle cx="11.5" cy="15.5" r="1.5" fill="currentColor" />
      <circle cx="20.5" cy="15.5" r="1.5" fill="currentColor" />

      {/* Mouth */}
      <rect x="12" y="21" width="8" height="2" rx="1" fill="white" opacity="0.7" />

      {/* Ears */}
      <rect x="1" y="12" width="3" height="9" rx="1.5" fill="currentColor" opacity="0.6" />
      <rect x="28" y="12" width="3" height="9" rx="1.5" fill="currentColor" opacity="0.6" />
    </svg>
  );
}
