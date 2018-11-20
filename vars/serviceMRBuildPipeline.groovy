def call(body) {
    def connection = new URL("https://ddadmin:AlternativelyNonsense@nexus.tools.tools178.k8syard.com/service/siesta/rest/beta/search?name=sws").openConnection()

    connection.setRequestMethod("GET")
    println(connection.getInputStream().getText())
}
