export function newPasswordError(value: string): string {
  const length = Array.from(value).length;
  if (length < 7 || length > 20) return "비밀번호는 7~20자로 입력해 주세요.";
  if (new TextEncoder().encode(value).length > 72)
    return "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.";
  return "";
}
