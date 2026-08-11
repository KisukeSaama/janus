import { useId, useState, type ComponentPropsWithoutRef, type ReactNode } from 'react';

import { useI18n } from '../i18n';

export function FormLayout({
  onSubmit,
  submitLabel,
  submitDisabled = false,
  error,
  children,
}: {
  onSubmit: (e: React.FormEvent<HTMLFormElement>) => void | Promise<void>;
  submitLabel: string;
  submitDisabled?: boolean;
  error: string;
  children: ReactNode;
}) {
  const { t } = useI18n();
  const [busy, setBusy] = useState(false);
  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setBusy(true);
        try {
          await onSubmit(e);
        } finally {
          setBusy(false);
        }
      }}
      className="flex flex-col"
    >
      <div className="space-y-5">{children}</div>
      {error && (
        <p role="alert" className="mt-5 rounded-panel border border-bad/40 bg-bad-wash px-3 py-2.5 text-sm">
          {error}
        </p>
      )}
      <div className="mt-8 border-t border-line pt-5">
        <button type="submit" className="btn btn-primary w-full" disabled={busy || submitDisabled}>
          {busy ? t('common.working') : submitLabel}
        </button>
      </div>
    </form>
  );
}

export function Field({
  label,
  hint,
  data,
  ...rest
}: ComponentPropsWithoutRef<'input'> & { label: string; hint?: string; data?: boolean }) {
  const id = useId();
  return (
    <div>
      <label htmlFor={id} className="stamp mb-2 block text-text-2">
        {label}
      </label>
      <input id={id} className={`field ${data ? 'data' : ''}`} {...rest} />
      {hint && <p className="mt-1.5 text-xs text-text-2">{hint}</p>}
    </div>
  );
}

/**
 * A short list, edited as lines rather than as rows.
 *
 * <p>For the handful of values where a repeater would be more chrome than content — origins, and
 * anything else that is a set of short strings. The monospace option applies where the value is
 * matched literally, so a typo is visible as a typo.
 */
export function TextAreaField({
  label,
  hint,
  data,
  ...rest
}: ComponentPropsWithoutRef<'textarea'> & { label: string; hint?: string; data?: boolean }) {
  const id = useId();
  return (
    <div>
      <label htmlFor={id} className="stamp mb-2 block text-text-2">
        {label}
      </label>
      <textarea id={id} rows={3} className={`field h-auto py-2 ${data ? 'data' : ''}`} {...rest} />
      {hint && <p className="mt-1.5 text-xs text-text-2">{hint}</p>}
    </div>
  );
}

export type Option = string | { value: string; label: string };

export function SelectField({
  label,
  hint,
  options,
  ...rest
}: ComponentPropsWithoutRef<'select'> & { label: string; hint?: string; options: Option[] }) {
  const id = useId();
  return (
    <div>
      <label htmlFor={id} className="stamp mb-2 block text-text-2">
        {label}
      </label>
      <select id={id} className="field" {...rest}>
        {options.map((o) => {
          const value = typeof o === 'string' ? o : o.value;
          const text = typeof o === 'string' ? o.replaceAll('_', ' ') : o.label;
          return (
            <option key={value} value={value}>
              {text}
            </option>
          );
        })}
      </select>
      {hint && <p className="mt-1.5 text-xs text-text-2">{hint}</p>}
    </div>
  );
}

export type Choice = { value: string; label: string; hint?: string; note?: ReactNode };

/**
 * A small set of consequential, unequal options, each of which needs a sentence. A select would hide
 * those sentences behind a click, and the whole point of the setup flow is that nothing consequential
 * is chosen blind. The radio itself stays visible: selection is a mark and a border, never hue alone.
 */

/**
 * A small set of consequential, unequal options, each of which needs a sentence. A select would hide
 * those sentences behind a click, and the whole point of the setup flow is that nothing consequential
 * is chosen blind. The radio itself stays visible: selection is a mark and a border, never hue alone.
 */
export function ChoiceField({
  label,
  name,
  value,
  options,
  onChange,
}: {
  label: string;
  name: string;
  value: string;
  options: Choice[];
  onChange: (value: string) => void;
}) {
  const groupId = useId();
  return (
    <fieldset>
      <legend id={groupId} className="stamp mb-2 text-text-2">
        {label}
      </legend>
      <div className="space-y-2">
        {options.map((option) => {
          const selected = option.value === value;
          return (
            <label
              key={option.value}
              className={`flex cursor-pointer items-start gap-3 rounded-control border px-3 py-2.5 transition-colors duration-150 has-[:focus-visible]:outline has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-accent ${
                selected ? 'border-accent bg-accent-wash' : 'border-line hover:bg-sunk'
              }`}
            >
              <input
                type="radio"
                name={name}
                value={option.value}
                checked={selected}
                onChange={() => onChange(option.value)}
                className="mt-0.5 h-4 w-4 shrink-0 accent-[var(--c-accent)]"
              />
              <span className="min-w-0">
                <span className="block text-sm font-medium">{option.label}</span>
                {option.hint && <span className="mt-0.5 block text-xs text-text-2">{option.hint}</span>}
                {option.note}
              </span>
            </label>
          );
        })}
      </div>
    </fieldset>
  );
}

export function CheckField({
  label,
  hint,
  ...rest
}: ComponentPropsWithoutRef<'input'> & { label: string; hint?: string }) {
  const id = useId();
  return (
    <div className="flex items-start gap-2.5">
      <input id={id} type="checkbox" className="mt-0.5 h-4 w-4 accent-[var(--c-accent)]" {...rest} />
      <div>
        <label htmlFor={id} className="text-sm">
          {label}
        </label>
        {hint && <p className="mt-1 text-xs text-text-2">{hint}</p>}
      </div>
    </div>
  );
}
