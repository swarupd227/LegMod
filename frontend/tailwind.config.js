/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // Surfaces
        ink:        '#0B1220',          // deepest text / top bar
        surface:    '#FFFFFF',
        canvas:     '#F7F8FA',          // page background
        subtle:     '#EFF1F4',          // hover background
        line:       '#E4E7EC',          // 1px borders
        line2:      '#D4D8DE',          // stronger borders
        // Brand / accents
        navy:       '#0B1220',          // brand primary (also used as ink)
        brand:      '#1F4FD9',          // primary accent (calls to action)
        'brand-50': '#EEF2FF',
        'brand-100':'#DCE5FE',
        'brand-700':'#1B43BC',
        // Agent / AI surface
        agent:      '#6E47E5',
        'agent-50': '#F2EEFE',
        'agent-100':'#E5DEFD',
        // Semantic
        ok:         '#0F8A5F',
        'ok-50':    '#E6F6EE',
        warn:       '#B36B00',
        'warn-50':  '#FFF4E0',
        err:        '#B23A48',
        'err-50':   '#FCEBEE',
        info:       '#1F4FD9',
        // Text — fg-3 lifted from #6B7280 (4.4:1) to #5A6573 (5.4:1) so the
        // tertiary-text uses across the app (eyebrows, meta rows, badge labels)
        // pass WCAG AA at sub-14pt sizes.
        'fg-1':     '#0B1220',          // primary
        'fg-2':     '#3A4658',          // secondary
        'fg-3':     '#5A6573',          // tertiary / meta
        'fg-4':     '#8E96A2'           // very subtle (decorative only)
      },
      fontFamily: {
        sans: ['"Inter"', '"Inter Variable"', 'system-ui', 'sans-serif'],
        mono: ['"JetBrains Mono"', '"Fira Code"', 'Consolas', 'monospace']
      },
      fontSize: {
        '2xs': ['11px', { lineHeight: '14px', letterSpacing: '0.02em' }],
        'xs':  ['12px', { lineHeight: '16px' }],
        'sm':  ['13px', { lineHeight: '20px' }],
        'base':['14px', { lineHeight: '22px' }],
        'md':  ['15px', { lineHeight: '24px' }],
        'lg':  ['16px', { lineHeight: '24px' }],
        'xl':  ['18px', { lineHeight: '26px' }],
        '2xl': ['22px', { lineHeight: '30px', letterSpacing: '-0.01em' }],
        '3xl': ['28px', { lineHeight: '36px', letterSpacing: '-0.015em' }],
        '4xl': ['36px', { lineHeight: '44px', letterSpacing: '-0.02em' }]
      },
      borderRadius: {
        DEFAULT: '6px',
        sm:  '4px',
        md:  '6px',
        lg:  '8px',
        xl:  '12px',
        '2xl':'16px'
      },
      boxShadow: {
        // Soft, layered shadows tuned for light surfaces
        'xs':  '0 1px 2px 0 rgba(15, 23, 42, 0.04)',
        'sm':  '0 1px 2px rgba(15, 23, 42, 0.06), 0 1px 1px rgba(15, 23, 42, 0.04)',
        'md':  '0 4px 12px -2px rgba(15, 23, 42, 0.08), 0 2px 4px -1px rgba(15, 23, 42, 0.04)',
        'lg':  '0 12px 32px -8px rgba(15, 23, 42, 0.12), 0 4px 8px -2px rgba(15, 23, 42, 0.06)',
        'pop': '0 16px 48px -12px rgba(15, 23, 42, 0.18), 0 8px 16px -4px rgba(15, 23, 42, 0.08)',
        'inset-line': 'inset 0 -1px 0 rgba(15, 23, 42, 0.06)'
      },
      backgroundImage: {
        'noise': "url(\"data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='160' height='160'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='2' stitchTiles='stitch'/></filter><rect width='100%' height='100%' filter='url(%23n)' opacity='0.04'/></svg>\")",
        'header-grad': 'linear-gradient(180deg, #0B1220 0%, #131C30 100%)',
        'agent-grad':  'linear-gradient(135deg, #F2EEFE 0%, #FFFFFF 60%)'
      },
      transitionTimingFunction: {
        'soft': 'cubic-bezier(0.22, 1, 0.36, 1)'
      },
      keyframes: {
        shimmer: {
          '0%':   { backgroundPosition: '-400px 0' },
          '100%': { backgroundPosition: '400px 0' }
        },
        pulseDot: {
          '0%, 100%': { opacity: 1 },
          '50%':      { opacity: 0.35 }
        }
      },
      animation: {
        shimmer:  'shimmer 1.6s ease-in-out infinite',
        pulseDot: 'pulseDot 1.6s ease-in-out infinite'
      }
    }
  },
  plugins: []
};
