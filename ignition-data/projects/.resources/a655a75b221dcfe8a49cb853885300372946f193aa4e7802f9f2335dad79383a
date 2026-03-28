def doGet(request, session):
	def build_greeting(name):
		prefix = "Hello"
		separator = ", "
		suffix = "!"
		greeting = "Hello, " + name + "!"
		return greeting

	name = "Debugger"
	message = build_greeting(name)
	result = {"greeting": message, "name": name, "source": "webdev"}
	return {"json": result}
