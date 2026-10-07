package dev.localphone.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NavigationTest {
    // Deliberately synthetic test data; no user addresses are seeded into the app.
    private fun place(id: String, name: String, aliases: List<String> = emptyList()) = UserPlace(id, name, aliases,
        Coordinates(36.123456, 128.123456), "합성 테스트 주소", "TEST", PlaceSource.MANUAL, 1, 1)
    private val home = place("home", "집", PlaceSlots.aliases.getValue("home"))
    private val office = place("office", "회사", PlaceSlots.aliases.getValue("office"))
    private val parents = place("parents_home", "본가", PlaceSlots.aliases.getValue("parents_home"))
    private class Repo(initial: List<UserPlace>) : UserPlacesRepository {
        val values = initial.associateBy { it.id }.toMutableMap()
        override suspend fun all() = values.values.toList()
        override suspend fun get(id: String) = values[id]
        override suspend fun saveConfirmed(place: UserPlace) { values[place.id] = place }
        override suspend fun delete(id: String) { values.remove(id) }
    }
    private class Search(var result: SearchResult = SearchResult()) : PlaceSearch {
        val queries = mutableListOf<String>()
        override suspend fun search(phrase: String): SearchResult { queries += phrase; return result }
    }
    private fun candidate(id: String, name: String) = PlaceCandidate(id, name, Coordinates(36.2, 128.2))
    private suspend fun destination(text: String, repo: Repo, search: Search): PolicyDecision =
        PolicyGate(PlaceResolver(repo, search)).prepare(BasicCommandPlanner().plan(text))

    @Test fun homePhrasesResolveToTheSameEntity() = runTest {
        val repo = Repo(listOf(home)); val search = Search()
        for (phrase in listOf("집", "우리집", "집으로", "집에")) {
            assertEquals("home", PlaceResolver(repo, search).resolve(phrase).resolved?.id, phrase)
        }
        for (phrase in listOf("집 가자", "집으로 가자", "우리집 가자", "집으로 네비 찍어줘")) {
            val decision = assertIs<PolicyDecision.Ready>(destination(phrase, repo, search))
            assertEquals("home", decision.destination?.id, phrase)
        }
        assertTrue(search.queries.isEmpty())
    }
    @Test fun officeAndParentsCommandsResolveLocally() = runTest {
        val repo = Repo(listOf(office, parents)); val search = Search()
        for ((text, expected) in listOf("회사로 가자" to "office", "직장으로 네비 찍어" to "office", "본가로 가자" to "parents_home")) {
            assertEquals(expected, assertIs<PolicyDecision.Ready>(destination(text, repo, search)).destination?.id)
        }
        assertTrue(search.queries.isEmpty())
    }
    @Test fun missingPrivateSlotsNeverLeaveTheDevice() = runTest {
        val search = Search(SearchResult(listOf(candidate("fake", "집"))))
        for (phrase in listOf("집", "home", "우리집", "회사로", "엄마집", "단골 주유소", "parents_home", "favorite_gas_station")) {
            val result = PlaceResolver(Repo(emptyList()), search).resolve(phrase)
            assertEquals(ResolutionStatus.NOT_FOUND, result.status)
            assertNotNull(result.setupSlot)
        }
        assertTrue(search.queries.isEmpty())
    }
    @Test fun canonicalNameHasPriorityOverAliases() = runTest {
        val other = place("other", "다른 곳", listOf("집"))
        assertEquals("home", PlaceResolver(Repo(listOf(home, other)), Search()).resolve("집").resolved?.id)
    }
    @Test fun duplicateAliasesAreAmbiguous() = runTest {
        val resolver = PlaceResolver(Repo(listOf(place("a", "카페 A", listOf("단골")), place("b", "카페 B", listOf("단골")))), Search())
        val result = resolver.resolve("단골")
        assertEquals(ResolutionStatus.AMBIGUOUS, result.status)
        assertNull(result.resolved)
        assertEquals(2, result.candidates.size)
    }
    @Test fun normalizationHandlesSpacingAndDirectionalParticles() = runTest {
        val resolver = PlaceResolver(Repo(listOf(home, parents)), Search())
        assertEquals("home", resolver.resolve("우리 집으로!").resolved?.id)
        assertEquals("parents_home", resolver.resolve("부모님 집으로").resolved?.id)
    }
    @Test fun realNamesEndingInRoAreNotDestroyed() = runTest {
        val repo = Repo(listOf(place("street", "종로"))); val search = Search()
        assertEquals("street", assertIs<PolicyDecision.Ready>(destination("종로 가자", repo, search)).destination?.id)
    }
    @Test fun fuzzyMatchAlwaysRequiresSelection() = runTest {
        val resolver = PlaceResolver(Repo(listOf(place("gas", "단골주유소"))), Search())
        val result = resolver.resolve("단골주유쇼")
        assertEquals(ResolutionStatus.AMBIGUOUS, result.status)
        assertNull(result.resolved)
    }
    @Test fun unknownStationCanResolveThroughSearch() = runTest {
        val search = Search(SearchResult(listOf(candidate("station", "수원역")), total = 1))
        val decision = assertIs<PolicyDecision.Ready>(destination("수원역 가자", Repo(emptyList()), search))
        assertEquals("station", decision.destination?.id)
        assertEquals(listOf("수원역"), search.queries)
    }
    @Test fun chainWithMultipleBranchesCannotNavigate() = runTest {
        val search = Search(SearchResult(listOf(candidate("s1", "스타벅스 A"), candidate("s2", "스타벅스 B")), 2))
        val decision = assertIs<PolicyDecision.Blocked>(destination("스타벅스 가자", Repo(emptyList()), search))
        assertEquals(ResolutionStatus.AMBIGUOUS, decision.resolution?.status)
    }
    @Test fun truncatedResponseCannotAppearUnique() = runTest {
        val resolver = PlaceResolver(Repo(emptyList()), Search(SearchResult(listOf(candidate("s1", "스타벅스 A")), 100)))
        assertEquals(ResolutionStatus.AMBIGUOUS, resolver.resolve("스타벅스").status)
    }
    @Test fun oneUnrelatedSearchResultStillRequiresSelection() = runTest {
        val resolver = PlaceResolver(Repo(emptyList()), Search(SearchResult(listOf(candidate("parking", "수원역 주차장")), 1)))
        assertEquals(ResolutionStatus.AMBIGUOUS, resolver.resolve("수원역").status)
    }
    @Test fun multiDestinationKeepsOriginalGoalForScreenFallbackWithoutPlaceSearch() = runTest {
        val search = Search()
        val result = destination("단골 주유소 들렀다가 집 가자", Repo(listOf(home)), search)
        val ready = assertIs<PolicyDecision.Ready>(result)
        assertEquals(listOf(Action.AppTask("지도", "단골 주유소 들렀다가 집 가자")), ready.appTasks)
        assertTrue(search.queries.isEmpty())
    }
    @Test fun missingHomePreservesNavigationAndMusicGoalsForUiFallback() = runTest {
        val search = Search()
        val decision = assertIs<PolicyDecision.Ready>(destination("집으로 네비 찍고 노래 재생해줘", Repo(emptyList()), search))
        assertEquals("집으로", decision.navigationGoal)
        assertNull(decision.destination)
        assertTrue(decision.resumeMedia)
        assertTrue(search.queries.isEmpty())
    }
    @Test fun compoundCommandPreparesNavigationAndMedia() = runTest {
        val ready = assertIs<PolicyDecision.Ready>(destination("집으로 네비 찍고 노래 재생해줘", Repo(listOf(home)), Search()))
        assertEquals("home", ready.destination?.id)
        assertTrue(ready.resumeMedia)
        val events = mutableListOf<String>()
        val nav = object : NavigationPort {
            override fun canLaunch(destination: PlaceCandidate) = true
            override fun launch(destination: PlaceCandidate): Boolean { events += "NAVER"; return true }
        }
        val music = object : MediaPort { override fun resume(): Boolean { events += "MEDIA"; return true } }
        val result = ActionExecutor(nav, music).execute(ready)
        assertTrue(result.launched && result.mediaResumed)
        assertEquals(listOf("NAVER", "MEDIA"), events)
    }
    @Test fun missingMapAppDoesNotStartMusic() {
        var resumed = false
        val nav = object : NavigationPort {
            override fun canLaunch(destination: PlaceCandidate) = false
            override fun launch(destination: PlaceCandidate) = error("Must not launch")
        }
        val music = object : MediaPort { override fun resume(): Boolean { resumed = true; return true } }
        val result = ActionExecutor(nav, music).execute(PolicyDecision.Ready(home.toCandidate(), true))
        assertFalse(result.launched); assertFalse(resumed)
    }
    @Test fun failedMapLaunchDoesNotStartMusic() {
        var resumed = false
        val nav = object : NavigationPort {
            override fun canLaunch(destination: PlaceCandidate) = true
            override fun launch(destination: PlaceCandidate) = false
        }
        val result = ActionExecutor(nav, object : MediaPort { override fun resume(): Boolean { resumed = true; return true } })
            .execute(PolicyDecision.Ready(home.toCandidate(), true))
        assertFalse(result.launched); assertFalse(resumed)
    }
    @Test fun negativeConditionalAndQuotedCommandsAreBlocked() = runTest {
        for (text in listOf("집 가지마", "집으로 가자 하지 마", "집 갈까", "집으로 가자라고 하면 무슨 뜻이야", "집으로 가면 노래 재생해줘")) {
            assertNotNull(BasicCommandPlanner().plan(text).unsupportedReason, text)
        }
    }
    @Test fun modelCannotInjectCoordinatesOrExtraTools() {
        for (call in listOf(
            RawToolCall("navigate", mapOf("destination" to "home", "latitude" to 37.0)),
            RawToolCall("navigate", mapOf("destination" to "37.1234,127.1234")),
            RawToolCall("send_sms", mapOf("body" to "hello")),
            RawToolCall("media_resume", mapOf("package" to "arbitrary")),
        )) assertNotNull(ToolPlanDecoder.decode(listOf(call)).unsupportedReason)
    }
    @Test fun modelPlanAllowsOnlyTypedDestinationAndResume() {
        val result = ToolPlanDecoder.decode(listOf(RawToolCall("navigate", mapOf("destination" to "본가")), RawToolCall("media_resume", emptyMap())))
        assertNull(result.unsupportedReason)
        assertEquals(listOf(Action.Navigate("본가"), Action.MediaResume), result.actions)
    }
    @Test fun modelCannotReplaceTheUsersDestination() {
        assertNotNull(PlanGrounding.validate(ToolPlan(listOf(Action.Navigate("수원역"))), "집으로 가자").unsupportedReason)
        assertNull(PlanGrounding.validate(ToolPlan(listOf(Action.Navigate("home"))), "우리집 가자").unsupportedReason)
        assertNotNull(PlanGrounding.validate(ToolPlan(listOf(Action.MediaResume)), "집으로 가자").unsupportedReason)
    }
    @Test fun modelWaypointsAndDuplicateMediaAreBlocked() {
        assertNotNull(ToolPlanDecoder.decode(listOf(RawToolCall("navigate", mapOf("destination" to "집")), RawToolCall("navigate", mapOf("destination" to "회사")))).unsupportedReason)
        assertNotNull(ToolPlanDecoder.decode(List(2) { RawToolCall("media_resume", emptyMap()) }).unsupportedReason)
    }
    @Test fun naverLinkEncodesNamesAndUsesCurrentLocation() {
        val uri = java.net.URI(NaverLinks.navigation(home.copy(canonicalName = "집 & 문=앞").toCandidate(), "dev.localphone.agent"))
        assertEquals("nmap", uri.scheme); assertEquals("navigation", uri.host)
        val query = uri.rawQuery
        assertTrue(query.contains("dlat=")); assertTrue(query.contains("dlng=")); assertFalse(query.contains("slat="))
        assertTrue(query.contains("%26")); assertTrue(query.contains("appname=dev.localphone.agent"))
    }
    @Test fun invalidCoordinatesAreRejected() {
        assertFailsWith<IllegalArgumentException> { Coordinates(Double.NaN, 127.0) }
        assertFailsWith<IllegalArgumentException> { Coordinates(95.0, 127.0) }
        assertFailsWith<IllegalArgumentException> { NaverLinks.navigation(candidate("foreign", "foreign").copy(coordinates = Coordinates(0.0, 0.0)), "dev.localphone.agent") }
    }
    @Test fun shareCanBeRegisteredAndResolvedByAlias() = runTest {
        val result = assertIs<ShareResult.CoordinatesFound>(NaverShareParser.parse("nmap://place?lat=36.123456&lng=128.123456&name=%EC%82%AC%EB%AC%B4%EC%8B%A4"))
        val repo = Repo(emptyList())
        repo.saveConfirmed(office.copy(coordinates = result.candidate.coordinates, source = PlaceSource.SHARED_LINK))
        assertEquals("office", PlaceResolver(repo, Search()).resolve("직장으로").resolved?.id)
    }
    @Test fun shortShareUrlRequiresSelectionAndNoNetwork() {
        val result = NaverShareParser.parse("[네이버 지도]\n어떤 카페\nhttps://naver.me/Example")
        assertEquals("어떤 카페", assertIs<ShareResult.NeedsSelection>(result).name)
    }
    @Test fun mapCenterCoordinatesCannotBecomeADestination() {
        assertIs<ShareResult.NeedsSelection>(NaverShareParser.parse("https://map.naver.com/p?c=128.1,36.1,15,0,0,0,dh"))
    }
    @Test fun forgedSharesAndDuplicateParametersAreRejected() {
        for (text in listOf("https://naver.me.evil.example/a", "https://user@naver.me/a", "http://naver.me/a", "file:///private/data", "nmap://place?lat=36&lat=37&lng=128&name=x", "nmap://navigation?dlat=36&dlng=128&dname=x")) {
            assertIs<ShareResult.Rejected>(NaverShareParser.parse(text), text)
        }
    }
    @Test fun searchResponseUsesWgs84AndKeepsBranches() {
        val json = """{"total":2,"items":[{"title":"<b>카페</b> A","mapx":"1282000000","mapy":"362000000","roadAddress":"테스트 A"},{"title":"카페 B","mapx":"1283000000","mapy":"363000000","address":"테스트 B"}]}"""
        val result = NaverSearchResponse.decode(json)
        assertFalse(result.error); assertEquals(2, result.candidates.size)
        assertEquals(Coordinates(36.2, 128.2), result.candidates.first().coordinates)
        assertEquals("카페 A", result.candidates.first().name)
    }
    @Test fun badSearchCoordinatesCannotMakeAResultUnique() {
        val json = """{"total":2,"items":[{"title":"A","mapx":"1282000000","mapy":"362000000"},{"title":"B","mapx":"311277","mapy":"552097"}]}"""
        assertTrue(NaverSearchResponse.decode(json).error)
        assertTrue(NaverSearchResponse.decode("{}").error)
    }
    @Test fun missingSearchPermissionAndErrorsAreDistinct() = runTest {
        val search = Search(SearchResult(permissionRequired = true))
        val resolver = PlaceResolver(Repo(emptyList()), search)
        assertEquals(ResolutionStatus.PERMISSION_REQUIRED, resolver.resolve("카페").status)
        search.result = SearchResult(error = true)
        assertEquals(ResolutionStatus.ERROR, resolver.resolve("카페").status)
        search.result = SearchResult()
        assertEquals(ResolutionStatus.NOT_FOUND, resolver.resolve("카페").status)
    }
    @Test fun deletingAPlaceRemovesAllItsAliases() = runTest {
        val repo = Repo(listOf(home)); repo.delete("home")
        assertEquals(ResolutionStatus.NOT_FOUND, PlaceResolver(repo, Search()).resolve("우리집").status)
    }
    @Test fun unavailableRepositoryReturnsErrorWithoutExternalSearch() = runTest {
        val broken = object : UserPlacesRepository {
            override suspend fun all(): List<UserPlace> = error("Unavailable storage")
            override suspend fun get(id: String): UserPlace? = error("Unavailable storage")
            override suspend fun saveConfirmed(place: UserPlace) = error("Unavailable storage")
            override suspend fun delete(id: String) = error("Unavailable storage")
        }
        val search = Search()
        assertEquals(ResolutionStatus.ERROR, PlaceResolver(broken, search).resolve("집").status)
        assertTrue(search.queries.isEmpty())
    }
}
