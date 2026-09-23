"use client";

import { useLayoutEffect, useRef, useState } from "react";

function formatBirthDate(input: string) {
  const digits = input.replace(/\D/g, "").slice(0, 8);
  if (digits.length < 4) return digits;
  if (digits.length < 6) return `${digits.slice(0, 4)}-${digits.slice(4)}`;
  return `${digits.slice(0, 4)}-${digits.slice(4, 6)}-${digits.slice(6)}`;
}

function caretAfterDigits(value: string, count: number) {
  if (count === 0) return 0;
  let digits = 0;
  for (let index = 0; index < value.length; index += 1) {
    if (/\d/.test(value[index])) digits += 1;
    if (digits === count) return value[index + 1] === "-" ? index + 2 : index + 1;
  }
  return value.length;
}

export function BirthDateInput({ defaultValue = "" }: { defaultValue?: string }) {
  const [value, setValue] = useState(() => formatBirthDate(defaultValue));
  const textInput = useRef<HTMLInputElement>(null);
  const pendingCaret = useRef<number | null>(null);

  useLayoutEffect(() => {
    if (pendingCaret.current !== null) {
      textInput.current?.setSelectionRange(pendingCaret.current, pendingCaret.current);
      pendingCaret.current = null;
    }
  }, [value]);

  function changeText(input: HTMLInputElement) {
    const count = (input.value.slice(0, input.selectionStart ?? input.value.length).match(/\d/g) ?? []).length;
    const formatted = formatBirthDate(input.value);
    const caret = caretAfterDigits(formatted, count);
    if (formatted === value) {
      input.value = value;
      input.setSelectionRange(caret, caret);
      return;
    }
    pendingCaret.current = caret;
    setValue(formatted);
  }

  function handleSeparatorDelete(event: React.KeyboardEvent<HTMLInputElement>) {
    const input = event.currentTarget;
    const start = input.selectionStart;
    if (start === null || start !== input.selectionEnd) return;
    const backwards = event.key === "Backspace" && value[start - 1] === "-";
    const forwards = event.key === "Delete" && value[start] === "-";
    if (!backwards && !forwards) return;
    event.preventDefault();
    const digits = value.replace(/\D/g, "");
    const before = (value.slice(0, start).match(/\d/g) ?? []).length;
    const remove = backwards ? before - 1 : Math.min(before, digits.length - 1);
    const formatted = formatBirthDate(digits.slice(0, remove) + digits.slice(remove + 1));
    pendingCaret.current = caretAfterDigits(formatted, backwards ? remove : before);
    setValue(formatted);
  }

  return <div className="birth-date-field"><span>생년월일</span><div className="birth-date-input">
    <input
      ref={textInput}
      name="birthDate"
      type="text"
      inputMode="numeric"
      autoComplete="bday"
      aria-label="생년월일 숫자 8자리 입력 (yyyy-mm-dd)"
      placeholder="yyyy-mm-dd"
      pattern="[0-9]{4}-[0-9]{2}-[0-9]{2}"
      title="yyyy-mm-dd 형식으로 입력해 주세요."
      required
      value={value}
      onChange={(event) => changeText(event.currentTarget)}
      onKeyDown={handleSeparatorDelete}
    />
    <input
      type="date"
      aria-label="달력에서 생년월일 선택"
      title="달력에서 생년월일 선택"
      value={/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(value) ? value : ""}
      onChange={(event) => setValue(event.target.value)}
    />
  </div></div>;
}
