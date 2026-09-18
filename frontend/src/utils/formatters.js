export const money = (value) => new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(value || 0);
export const date = (value) => value ? new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium' }).format(new Date(value)) : '—';
export const daysUntil = (value) => value ? Math.ceil((new Date(`${value}T00:00:00`) - new Date()) / 86400000) : null;
