"use client";

import { useState } from "react";

export function BirthDateInput({ defaultValue = "" }: { defaultValue?: string }) {
  const [value, setValue] = useState(defaultValue);
  return <div className="birth-date-field"><span>생년월일</span><div className="birth-date-input">
    <input
      name="birthDate"
      type="text"
      inputMode="numeric"
      autoComplete="bday"
      aria-label="생년월일 직접 입력 (yyyy-mm-dd)"
      placeholder="yyyy-mm-dd"
      pattern="[0-9]{4}-[0-9]{2}-[0-9]{2}"
      title="yyyy-mm-dd 형식으로 입력해 주세요."
      required
      value={value}
      onChange={(event) => setValue(event.target.value)}
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
