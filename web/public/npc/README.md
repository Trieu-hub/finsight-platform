# NPC hướng dẫn — thư mục ảnh

Đây là nơi chứa ảnh cho **Vera** — nhân vật hướng dẫn (NPC) xuất hiện lần đầu người dùng
đăng nhập vào Vernfy, dẫn họ đi một vòng qua các chức năng.

## Cách dùng
Chỉ cần **thả ảnh vào thư mục này với ĐÚNG tên file bên dưới**. Vite phục vụ mọi thứ trong
`web/public/` ở gốc web, nên `web/public/npc/welcome.png` sẽ tự có tại đường dẫn `/npc/welcome.png`
mà component đã trỏ sẵn — không cần sửa code.

> Thiếu ảnh nào cũng **không sao**: chỗ đó tự hiện avatar emoji thay thế, giao diện không vỡ.
> Bạn có thể thêm dần từng ảnh.

## Danh sách file cần (mỗi bước 1 ảnh)
| Tên file | Dùng ở bước | Gợi ý nội dung ảnh |
|---|---|---|
| `welcome.png`        | Chào mừng          | Vera vẫy tay chào |
| `dashboard.png`      | Giới thiệu Dashboard | Vera chỉ vào biểu đồ tổng quan |
| `transactions.png`   | Giới thiệu Transactions | Vera cầm hoá đơn / sổ ghi chép |
| `budgets.png`        | Giới thiệu Budgets | Vera bên cạnh chiếc ví có hạn mức |
| `wallets.png`        | Giới thiệu Wallets | Vera cầm ví/thẻ |
| `analytics.png`      | Giới thiệu Analytics | Vera chỉ vào biểu đồ phân tích |
| `notifications.png`  | Giới thiệu chuông báo | Vera bên cạnh chiếc chuông |
| `finish.png`         | Kết thúc           | Vera giơ ngón tay cái / chúc mừng |

## Yêu cầu ảnh (khuyến nghị)
- Định dạng: **PNG nền trong suốt** (đẹp nhất), hoặc JPG/SVG cũng được.
- Kích thước: gần **vuông**, khoảng **256–512px** mỗi cạnh.
- Phong cách nhất quán (cùng một nhân vật ở các tư thế khác nhau).

## Muốn đổi tên nhân vật / thêm bớt bước?
Sửa mảng `STEPS` trong `web/src/components/OnboardingTour.tsx` — mỗi phần tử là một bước
(`image`, `title`, `body`). Đổi `image` để trỏ tới tên file khác nếu cần.
