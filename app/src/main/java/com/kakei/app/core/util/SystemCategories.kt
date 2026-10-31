package com.kakei.app.core.util

/**
 * Ids of the system categories seeded by the Kakei backend (ids assigned in migration 14).
 * They are stable across servers, so the client can route keyword matches to them.
 */
object SystemCategories {
    const val GROCERIES = "01a19376-6e4b-78e1-849b-f4b5b9f28fbd"
    const val RESTAURANTS = "01a19376-6e50-7d6b-829d-ace5e5036a63"
    const val COFFEE = "01a19376-6e54-7ec3-84fd-070698996bb2"
    const val FOOD_DELIVERY = "01a19376-6e58-74a0-89e2-f1e2e32c0485"
    const val TRANSIT = "01a19376-6e61-77ac-b06c-64f7da3189d8"
    const val FUEL = "01a19376-6e6e-77f8-b389-d3868bc3ed84"
    const val TAXI = "01a19376-6e65-7da1-82c0-2d06761eaf67"
    const val RENT = "01a19376-6e96-7f3d-9a4c-a80a7e0e9f9e"
    const val UTILITIES = "01a19376-6e9a-721d-978d-10c3ce772a16"
    const val PHONE_INTERNET = "01a19376-6ee5-777c-9cc5-9647f2922185"
    const val ELECTRONICS = "01a19376-6eac-7553-8e78-b8baccaa70d6"
    const val CLOTHING = "01a19376-6eb0-75b3-b5a2-a33e81d7c58c"
    const val PHARMACY = "01a19376-6ebd-7b33-ac82-2ebd45f49129"
    const val FITNESS = "01a19376-6ec2-7d23-9879-9405f0106b77"
    const val STREAMING = "01a19376-6ecf-7964-a5f5-77e2ba032f36"
    const val INCOME = "01a19376-6f4a-75a6-89f8-c27590d505fe"
    const val SALARY = "01a19376-6f4f-7cfb-ab3e-36ff0b0a3ac9"
    const val REFUNDS = "01a19376-6f53-765b-8523-82590d237aef"
    const val INTEREST = "01a19376-6f57-7256-9883-904ee104e6df"
    const val CASH_WITHDRAWAL = "01a19376-6f69-7005-b62a-a5a3d19a65c2"
    const val PARKING = "01a19376-6e72-700f-940f-dfba6489fbd9"
    const val CAR_MAINTENANCE = "01a19376-6e77-7343-b63a-a82f6652a7a2"
    const val AI_CLOUD = "01a19376-6eea-77ab-9d89-a7969fccf522"
    const val GAMES = "01a19376-6ed8-7cc2-b0ab-e5023c37817e"
    const val APPS = "01a19376-6eee-71a6-9a45-730b0a96454f"
    const val BOOKS = "01a19376-6edc-7989-92f8-60a8846ee407"
    const val TAXES = "01a19376-6f22-7cfa-9b77-da899b7da4b7"
    const val BANK_FEES = "01a19376-6f26-7f86-934f-6aba6a9b2126"
    const val INSURANCE = "01a19376-6f34-78ef-8f8c-b46776027711"
    const val TUITION = "01a19376-6f0c-7801-9452-9606663a4014"
    const val TRAVEL_TICKETS = "01a19376-6e84-7cd1-84e1-ba0ad86d080f"
    const val ACCOMMODATION = "01a19376-6e8d-780b-a1aa-3e237aeef9ee"
}
