import { useId, type ComponentProps, type ReactNode } from "react";

/**
 * Form pieces with the site's own classes, in Tailwind v4 names: v3's
 * `shadow-sm` is v4's `shadow-xs`.
 *
 * Fields make their own ids: Next.js keeps pages the user left mounted (hidden)
 * for Back and Forward, so two forms can be in the document at once.
 */

const FIELD =
  "block w-full rounded-md border-0 px-2 py-1.5 text-gray-900 shadow-xs ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-blue-600 disabled:bg-gray-50";

export function Label({ htmlFor, children }: { htmlFor: string; children: ReactNode }) {
  return (
    <label htmlFor={htmlFor} className="block text-sm font-semibold text-gray-800">
      {children}
    </label>
  );
}

export function TextField({ label, name, className, ...props }: ComponentProps<"input"> & { label: string; name: string }) {
  const id = useId();
  return (
    <div className={className}>
      <Label htmlFor={id}>{label}</Label>
      <div className="mt-2">
        <input id={id} name={name} className={FIELD} {...props} />
      </div>
    </div>
  );
}

export function TextArea({
  label,
  name,
  className,
  ...props
}: ComponentProps<"textarea"> & { label: string; name: string }) {
  const id = useId();
  return (
    <div className={className}>
      <Label htmlFor={id}>{label}</Label>
      <div className="mt-2">
        <textarea id={id} name={name} className={`${FIELD} resize-none`} {...props} />
      </div>
    </div>
  );
}

/** The small bordered select of the filters and the rating picker. */
export function Select({ className = "", ...props }: ComponentProps<"select">) {
  return <select className={`rounded-md border border-gray-300 bg-white p-1 ${className}`} {...props} />;
}

export function PrimaryButton({ className = "", ...props }: ComponentProps<"button">) {
  return (
    <button
      className={`flex w-full cursor-pointer justify-center rounded-md bg-blue-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white hover:bg-blue-500 disabled:cursor-default disabled:opacity-60 ${className}`}
      {...props}
    />
  );
}

/** The sample's small uppercase "Details" button, as a button. */
export function SmallButton({ tone = "blue", className = "", ...props }: ComponentProps<"button"> & { tone?: "blue" | "red" }) {
  const colors = tone === "red" ? "bg-red-600 hover:bg-red-500" : "bg-blue-500 hover:bg-blue-400";
  return (
    <button
      className={`cursor-pointer rounded-md px-4 py-2 text-xs uppercase text-white disabled:cursor-default disabled:opacity-60 ${colors} ${className}`}
      {...props}
    />
  );
}

export const SMALL_LINK = "rounded-md bg-blue-500 px-4 py-2 text-xs uppercase text-white hover:bg-blue-400";

export function ErrorMessage({ children }: { children: ReactNode }) {
  if (!children) {
    return null;
  }
  return (
    <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700 ring-1 ring-inset ring-red-200">
      {children}
    </p>
  );
}

export function Spinner({ label = "Loading…" }: { label?: string }) {
  return (
    <div role="status" className="flex items-center justify-center gap-2 py-8 text-sm text-gray-500">
      <span className="size-4 animate-spin rounded-full border-2 border-gray-300 border-t-blue-500" />
      {label}
    </div>
  );
}

/** The centred card of the sign-in, register and product forms. */
export function FormCard({ title, wide, children }: { title?: string; wide?: boolean; children: ReactNode }) {
  return (
    <div className="flex flex-col justify-center px-4 py-12">
      {title && (
        <h2 className="mt-6 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900">{title}</h2>
      )}
      <div className={`mx-auto mt-10 w-full rounded-lg p-6 shadow-2xl ${wide ? "max-w-2xl" : "max-w-md"}`}>
        {children}
      </div>
    </div>
  );
}
