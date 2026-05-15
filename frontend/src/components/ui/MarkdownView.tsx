import React from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

/**
 * Production-grade markdown renderer used for closure documents and any
 * other inline markdown surface. Handles GFM tables, fenced code blocks,
 * task lists, autolinks, and strikethrough. Replaces the hand-rolled
 * "MarkdownLite" implementation that didn't render tables.
 */
export function MarkdownView({
  body, className = ''
}: { body: string; className?: string }) {
  return (
    <article className={`markdown-body ${className}`}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          h1: (props) => (
            <h1 className="text-2xl font-semibold tracking-tight text-fg-1 mt-2 mb-3" {...props} />
          ),
          h2: (props) => (
            <h2 className="text-lg font-semibold text-fg-1 mt-7 mb-2 pb-1 border-b border-line" {...props} />
          ),
          h3: (props) => (
            <h3 className="text-base font-semibold text-fg-1 mt-5 mb-2" {...props} />
          ),
          p: (props) => <p className="text-sm text-fg-1 leading-relaxed my-2" {...props} />,
          ul: (props) => <ul className="list-disc pl-6 space-y-1 my-2 text-sm text-fg-1" {...props} />,
          ol: (props) => <ol className="list-decimal pl-6 space-y-1 my-2 text-sm text-fg-1" {...props} />,
          li: (props) => <li className="leading-relaxed" {...props} />,
          a: ({ href, ...rest }) => (
            <a
              href={href}
              target={href?.startsWith('http') ? '_blank' : undefined}
              rel={href?.startsWith('http') ? 'noreferrer noopener' : undefined}
              className="text-brand hover:underline"
              {...rest}
            />
          ),
          code: ({ className, children, ...rest }) => {
            const isBlock = (className ?? '').includes('language-');
            if (isBlock) {
              return (
                <code className={`${className ?? ''} block`} {...rest}>
                  {children}
                </code>
              );
            }
            return <code className="code-chip">{children}</code>;
          },
          pre: (props) => (
            <pre
              className="surface-soft my-3 p-3 text-2xs font-mono whitespace-pre-wrap break-words text-fg-1 overflow-auto"
              {...props}
            />
          ),
          table: (props) => (
            <div className="my-4 overflow-x-auto surface-soft">
              <table className="w-full text-sm border-collapse" {...props} />
            </div>
          ),
          thead: (props) => (
            <thead className="bg-canvas border-b border-line text-2xs uppercase tracking-wider text-fg-3" {...props} />
          ),
          th: (props) => <th className="text-left font-medium px-3 py-2 align-top" {...props} />,
          td: (props) => <td className="px-3 py-2 border-t border-line/60 align-top text-fg-1" {...props} />,
          blockquote: (props) => (
            <blockquote className="border-l-2 border-brand pl-3 my-3 text-fg-2 text-sm italic" {...props} />
          ),
          hr: () => <hr className="my-6 border-line" />,
          strong: (props) => <strong className="font-semibold text-fg-1" {...props} />,
          em: (props) => <em className="italic" {...props} />
        }}
      >
        {body}
      </ReactMarkdown>
    </article>
  );
}
