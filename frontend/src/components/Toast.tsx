import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';

type Say = (message: string) => void;

const ToastContext = createContext<{ message: string; say: Say }>({ message: '', say: () => undefined });

/** One toast at a time, 2.8 s, a new one replaces the previous — as in the design. */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState('');
  const timer = useRef<number | undefined>(undefined);
  const say = useCallback<Say>((next) => {
    window.clearTimeout(timer.current);
    setMessage(next);
    timer.current = window.setTimeout(() => setMessage(''), 2800);
  }, []);
  useEffect(() => () => window.clearTimeout(timer.current), []);
  const value = useMemo(() => ({ message, say }), [message, say]);
  return <ToastContext.Provider value={value}>{children}</ToastContext.Provider>;
}

export const useToast = (): Say => useContext(ToastContext).say;
export const useToastMessage = (): string => useContext(ToastContext).message;
