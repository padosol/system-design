# URL 단축 서비스 용어집

`url-shortener` 도메인 고유 용어. 아키텍처·운영 공통어는 루트 [`docs/glossary.md`](../glossary.md) 참고.

## 단축 키 (Short Key)
원본 URL을 가리키는 짧고 유일한 식별자. Base62 7자리 채택.

## Base62
`a–z A–Z 0–9` 62문자로 수를 표현하는 인코딩. 짧고 URL-safe한 키 생성에 쓴다.

## 원본 URL (Long URL)
단축 대상이 되는 원래의 긴 URL. 단축 키로 접근 시 이곳으로 리다이렉트한다.

## 커스텀 별칭 (Custom Alias)
사용자가 직접 지정하는 단축 키(선택 기능).

## 리다이렉트 (Redirect)
단축 키 접근을 원본 URL로 보내는 응답(`301`/`302`).
