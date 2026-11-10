# Air Mouse Bluetooth

**Android-приложение, превращающее телефон в Bluetooth-мышь для телевизора или компьютера.**

Проект экспериментальный. Он использует системный профиль **Bluetooth HID Device**: телефон должен уметь выступать Bluetooth-мышью, а телевизор — принимать Bluetooth HID-мыши.

## Возможности

- Сенсорный тачпад для перемещения курсора.
- Режим Air Mouse: управление курсором движением телефона с помощью гироскопа.
- Регулировка чувствительности и сглаживания.
- Калибровка движения.
- Левый и правый клик.
- Подключение напрямую по Bluetooth — без Wi-Fi и без установки приложения на телевизор.

## Сборка и установка

APK собирается автоматически через GitHub Actions при изменениях в `main`.

1. Открой вкладку **Actions** и дождись успешной сборки.
2. Скачай артефакт **AirMouse-Bluetooth-APK** из завершённого запуска или APK из раздела **Releases**.
3. Установи APK на Android-телефон.
4. Включи Bluetooth на телефоне и телевизоре.
5. Выполни сопряжение в системных настройках Bluetooth телевизора/телефона.
6. Открой Air Mouse и выбери сопряжённый телевизор.

## Важно: совместимость

Android API Bluetooth HID Device доступен не на каждом телефоне: производитель должен реализовать соответствующую роль в Bluetooth-прошивке. Наличие Bluetooth само по себе этого не гарантирует.

Телевизор также должен поддерживать Bluetooth-мыши (HID). Поддержка клавиатур или пультов не обязательно означает поддержку мыши.

**На данный момент приложение не подтверждено на всех моделях телефонов и телевизоров.** Если телефон не предоставляет профиль HID Device или телевизор не принимает HID-мышь, приложение не сможет управлять курсором. Для диагностики сначала проверь возможность сопряжения обычной Bluetooth-мыши с телевизором.

## Текущий статус

- [x] Исходный проект Android.
- [x] Тачпад и управление гироскопом.
- [x] Настройки чувствительности и сглаживания.
- [x] Автоматическая сборка Debug APK.
- [ ] Проверка на реальном телефоне.
- [ ] Проверка совместимости с конкретными телевизорами.

Это тестовая сборка, не заявляется как стабильный релиз.

## English

An experimental Android app that turns a phone into a Bluetooth HID mouse. It offers a touchpad and a gyroscope-based Air Mouse mode, with sensitivity and smoothing controls.

Both ends must support Bluetooth HID mouse: the Android phone must implement the HID Device role, and the TV/computer must accept HID mice. Bluetooth support alone does not guarantee compatibility. No Wi-Fi and no companion app on the TV are required.

The project is experimental and still requires testing on real hardware.

## License

License not specified yet.
